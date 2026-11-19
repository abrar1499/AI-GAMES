package main

import (
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"rsc.io/qr"
)

// controlHandler serves the owner's control page: on this PC only, never through the internet link.
func controlHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; form-action 'none'")
		w.Write(controlHTML)
	})
	mux.HandleFunc("/c/ping", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "sharefolder") })
	mux.HandleFunc("/c/state", cState)
	mux.HandleFunc("/c/browse", cBrowse)
	mux.HandleFunc("/c/folder", post(cFolder))
	mux.HandleFunc("/c/readonly", post(cReadOnly))
	mux.HandleFunc("/c/password", post(cPassword))
	mux.HandleFunc("/c/explorer", post(func(w http.ResponseWriter, r *http.Request) {
		openFolder(R())
		writeJSON(w, map[string]any{"ok": true})
	}))
	mux.HandleFunc("/c/stop", post(cStop))
	mux.HandleFunc("/c/qr.png", cQR)
	mux.HandleFunc("/c/cf/login", post(func(w http.ResponseWriter, r *http.Request) {
		var b struct{ Again bool }
		readBody(r, &b)
		if !internetOn {
			jsonErr(w, 400, "The internet link is off (-no-internet).")
			return
		}
		go cfLogin(b.Again)
		writeJSON(w, map[string]any{"ok": true})
	}))
	mux.HandleFunc("/c/cf/domain", post(func(w http.ResponseWriter, r *http.Request) {
		var b struct{ Host string }
		if readBody(r, &b) != nil {
			jsonErr(w, 400, "Bad request")
			return
		}
		host, err := validHost(b.Host)
		if err != nil {
			jsonErr(w, 400, err.Error())
			return
		}
		if !internetOn {
			jsonErr(w, 400, "The internet link is off (-no-internet).")
			return
		}
		if !certExists() {
			jsonErr(w, 400, "Connect Cloudflare first (step 2).")
			return
		}
		go cfUseDomain(host)
		writeJSON(w, map[string]any{"ok": true})
	}))
	mux.HandleFunc("/c/cf/off", post(func(w http.ResponseWriter, r *http.Request) {
		cfStop()
		writeJSON(w, map[string]any{"ok": true})
	}))
	mux.HandleFunc("/c/visit", cVisit)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if h, _, err := net.SplitHostPort(host); err == nil {
			host = h
		}
		if host != "localhost" && host != "127.0.0.1" {
			http.Error(w, "Unknown address", 421)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		mux.ServeHTTP(w, r)
	})
}

// post: changes come only from the control page itself (no other website can trigger them).
func post(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.Header.Get("X-ShareFolder") != "1" {
			jsonErr(w, 403, "Refused")
			return
		}
		h(w, r)
	}
}

func folderName(p string) string {
	if p == "" {
		return ""
	}
	n := filepath.Base(p)
	if n == string(filepath.Separator) || n == "." || strings.HasSuffix(n, ":") {
		return "Drive " + strings.TrimSuffix(filepath.VolumeName(p), ":")
	}
	return n
}

func cState(w http.ResponseWriter, r *http.Request) {
	stMu.Lock()
	st := status
	stMu.Unlock()
	var lan []string
	if lanMode {
		for _, ip := range lanIPs() {
			lan = append(lan, fmt.Sprintf("http://%s:%d", ip, sharePort))
		}
	}
	writeJSON(w, map[string]any{
		"folder": R(), "name": folderName(R()), "readOnly": readOnlyF.Load(), "password": pw(),
		"link": st.Link, "short": st.Short, "shortNote": st.ShortNote, "permanent": st.Permanent,
		"status": st.Text, "working": st.Working, "lan": lan, "internet": internetOn, "cf": cfState(),
	})
}

func useFolder(w http.ResponseWriter, p string) {
	abs, problem := checkFolder(p)
	if problem != "" {
		jsonErr(w, 400, problem)
		return
	}
	setRoot(abs)
	saveSettings()
	cleanParts()
	writeJSON(w, map[string]any{"folder": abs, "name": folderName(abs)})
}

// place is a folder or drive offered in the folder chooser.
type place struct {
	Name string `json:"name"`
	Path string `json:"path"`
	Kind string `json:"kind,omitempty"` // "drive", "home" or ""
}

// cBrowse lists folders for the owner's folder chooser: with no path, the usual folders and the drives;
// with a path, the folders inside it. It never lists files and never changes anything.
func cBrowse(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimSpace(r.URL.Query().Get("p"))
	if p == "" {
		var places []place
		if home, err := os.UserHomeDir(); err == nil {
			for _, n := range []string{"Desktop", "Documents", "Downloads", "Pictures", "Videos", "Music"} {
				for _, cand := range []string{filepath.Join(home, n), filepath.Join(home, "OneDrive", n)} {
					if st, err := os.Stat(cand); err == nil && st.IsDir() {
						places = append(places, place{Name: n, Path: cand, Kind: "home"})
						break
					}
				}
			}
		}
		writeJSON(w, map[string]any{"path": "", "places": places, "drives": drives()})
		return
	}
	abs, problem := checkFolder(p)
	if problem != "" {
		jsonErr(w, 404, problem)
		return
	}
	des, err := os.ReadDir(abs)
	if err != nil {
		jsonErr(w, 403, "This folder can't be opened (Windows doesn't allow it).")
		return
	}
	dirs := []place{}
	for _, d := range des {
		fi, err := d.Info()
		if err != nil || !fi.IsDir() || fi.Mode()&(fs.ModeSymlink|fs.ModeIrregular) != 0 || hiddenAttr(fi) || strings.HasPrefix(d.Name(), ".") || strings.HasPrefix(d.Name(), "$") {
			continue
		}
		dirs = append(dirs, place{Name: d.Name(), Path: filepath.Join(abs, d.Name())})
	}
	sort.Slice(dirs, func(i, j int) bool { return strings.ToLower(dirs[i].Name) < strings.ToLower(dirs[j].Name) })
	parent := filepath.Dir(abs)
	if parent == abs {
		parent = "" // a drive: up goes back to the list of drives
	}
	writeJSON(w, map[string]any{"path": abs, "name": folderName(abs), "parent": parent, "dirs": dirs})
}

func cFolder(w http.ResponseWriter, r *http.Request) {
	var b struct{ Path string }
	if readBody(r, &b) != nil || strings.TrimSpace(b.Path) == "" {
		jsonErr(w, 400, "Paste a folder's path")
		return
	}
	useFolder(w, b.Path)
}

func cReadOnly(w http.ResponseWriter, r *http.Request) {
	var b struct{ On bool }
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	readOnlyF.Store(b.On)
	saveSettings()
	writeJSON(w, map[string]any{"readOnly": b.On})
}

func cPassword(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Password string
		New      bool
	}
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	p := b.Password
	if b.New {
		p = newPin()
	}
	if err := setPassword(p); err != nil {
		jsonErr(w, 400, err.Error())
		return
	}
	writeJSON(w, map[string]any{"password": pw()})
}

func cStop(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"ok": true})
	go func() {
		time.Sleep(300 * time.Millisecond)
		stMu.Lock()
		if tunnelCmd != nil && tunnelCmd.Process != nil {
			tunnelCmd.Process.Kill()
		}
		stMu.Unlock()
		os.Exit(0)
	}()
}

// cQR draws the QR code of one of the current links, for a phone to scan.
func cQR(w http.ResponseWriter, r *http.Request) {
	t := r.URL.Query().Get("t")
	stMu.Lock()
	ok := t != "" && (t == status.Link || t == status.Short)
	stMu.Unlock()
	for _, ip := range lanIPs() {
		if t == fmt.Sprintf("http://%s:%d", ip, sharePort) {
			ok = true
		}
	}
	if !ok {
		http.Error(w, "Unknown link", 404)
		return
	}
	code, err := qr.Encode(t, qr.M)
	if err != nil {
		http.Error(w, "No QR", 500)
		return
	}
	code.Scale = 8
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(code.PNG())
}

// cVisit opens the folder page on this PC, already signed in.
func cVisit(w http.ResponseWriter, r *http.Request) {
	oneTimeMu.Lock()
	oneTimeKey = randHex(16)
	k := oneTimeKey
	oneTimeMu.Unlock()
	http.Redirect(w, r, fmt.Sprintf("http://localhost:%d/?key=%s", sharePort, k), http.StatusFound)
}

var _ = json.Marshal
