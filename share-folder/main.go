// ShareFolder shares one folder of this PC over the internet, behind a password,
// in a page that works like Windows File Explorer: browse, upload, download,
// new folder, rename, delete, cut, copy and paste.
//
// Run it with no arguments (it asks which folder), or drop a folder onto the exe,
// or: ShareFolder.exe "D:\Photos" -port 8080 -pass mysecret -no-internet
//
// The internet link comes from a free Cloudflare quick tunnel (cloudflared), which
// the program downloads once into the user's settings folder. Files never leave the
// PC except to the people who open the link and know the password.
//
//go:debug winsymlink=0
package main

import (
	"archive/zip"
	"bufio"
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"io/fs"
	"log"
	"mime"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed web/index.html
var indexHTML []byte

const (
	hiddenPrefix = ".ShareFolder" // the trash and unfinished uploads live under names starting with this
	trashDir     = ".ShareFolder-Trash"
	partsDir     = ".ShareFolder-Uploads"
)

var (
	root     string // the shared folder, absolute and with symlinks resolved
	password string
	readOnly bool

	sessMu   sync.Mutex
	sessions = map[string]time.Time{}

	failMu sync.Mutex
	fails  = map[string][]time.Time{}

	oneTimeMu  sync.Mutex
	oneTimeKey string // opens the page on this PC without typing the password

	partLocks sync.Map // upload id -> *sync.Mutex: one piece at a time per upload
	finished  sync.Map // upload id -> final name, so a repeated last piece does not make a second copy

	protected   []fs.FileInfo // this program and its settings folder: never shown, changed or replaced
	globalFails atomic.Int64
	lanMode     bool
)

func lockFor(id string) *sync.Mutex {
	m, _ := partLocks.LoadOrStore(id, &sync.Mutex{})
	return m.(*sync.Mutex)
}

func main() {
	port := flag.Int("port", 8080, "port to listen on")
	pass := flag.String("pass", "", "set a new password (saved for next time)")
	noNet := flag.Bool("no-internet", false, "do not create an internet link (same WiFi/LAN only)")
	ro := flag.Bool("read-only", false, "visitors can only browse and download")
	noOpen := flag.Bool("no-browser", false, "do not open the browser on this PC")
	lan := flag.Bool("lan", false, "also share on this WiFi/LAN (Windows may ask to allow it)")
	// the folder may come before or after the options ("ShareFolder D:\Photos -port 9000")
	var positional []string
	rest := os.Args[1:]
	for {
		flag.CommandLine.Parse(rest)
		if flag.NArg() == 0 {
			break
		}
		positional = append(positional, flag.Arg(0))
		rest = flag.Args()[1:]
	}
	readOnly = *ro
	lanMode = *lan || *noNet

	fmt.Println()
	fmt.Println("  ==============================================")
	fmt.Println("   ShareFolder  -  share a folder of this PC")
	fmt.Println("  ==============================================")
	fmt.Println()

	dir := ""
	if len(positional) > 0 {
		dir = positional[0]
	}
	for {
		asked := dir == ""
		if asked {
			dir = askFolder()
		}
		abs, problem := checkFolder(dir)
		if problem == "" {
			root = abs
			break
		}
		if !asked {
			fatal(problem)
		}
		fmt.Println("  " + problem + " Please paste it again.")
		fmt.Println()
		dir = ""
	}
	if exe, err := os.Executable(); err == nil {
		if st, err := os.Stat(exe); err == nil {
			protected = append(protected, st)
		}
	}
	if st, err := os.Stat(configDir()); err == nil {
		protected = append(protected, st)
	}
	keepAwake()

	cleanParts()
	go func() {
		for range time.Tick(time.Hour) {
			cleanParts()
		}
	}()
	password = loadPassword(*pass)
	oneTimeKey = randHex(16)

	ln, usedPort := listen(*port)
	mux := http.NewServeMux()
	mux.HandleFunc("/", serveIndex)
	mux.HandleFunc("/api/login", apiLogin)
	mux.HandleFunc("/api/logout", apiLogout)
	mux.HandleFunc("/api/whoami", auth(apiWhoami))
	mux.HandleFunc("/api/list", auth(apiList))
	mux.HandleFunc("/api/search", auth(apiSearch))
	mux.HandleFunc("/api/raw", auth(apiRaw))
	mux.HandleFunc("/api/zip", auth(apiZip))
	mux.HandleFunc("/api/mkdir", auth(writer(apiMkdir)))
	mux.HandleFunc("/api/rename", auth(writer(apiRename)))
	mux.HandleFunc("/api/delete", auth(writer(apiDelete)))
	mux.HandleFunc("/api/paste", auth(writer(apiPaste)))
	mux.HandleFunc("/api/upload", auth(writer(apiUpload)))

	srv := &http.Server{Handler: securityHeaders(mux), ReadHeaderTimeout: 20 * time.Second, ReadTimeout: 15 * time.Minute, IdleTimeout: 2 * time.Minute}
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fatal("The server stopped: " + err.Error())
		}
	}()

	local := fmt.Sprintf("http://localhost:%d", usedPort)
	if !lanMode {
		fmt.Println("  (Add -lan to also open it on this WiFi without the internet.)")
	}
	fmt.Println("  Sharing folder : " + root)
	if readOnly {
		fmt.Println("  Mode           : read-only (browse and download)")
	}
	fmt.Println("  Password       : " + password)
	fmt.Println()
	fmt.Println("  On this PC     : " + local)
	if lanMode {
		for _, ip := range lanIPs() {
			fmt.Printf("  Same WiFi/LAN  : http://%s:%d\n", ip, usedPort)
		}
	}
	fmt.Println()
	if !*noOpen {
		openBrowser(local + "/?key=" + oneTimeKey)
	}
	if *noNet {
		fmt.Println("  Internet link is off (-no-internet).")
	} else {
		go startTunnel(usedPort)
	}
	fmt.Println("  Keep this window open while people use the link. Close it to stop sharing.")
	fmt.Println("  The PC is kept awake while sharing. Closing a laptop lid still stops it.")
	fmt.Println()
	select {}
}

// ------------------------------------------------------------------ startup

// checkFolder turns what the user typed into the folder to share, or explains what is wrong with it.
func checkFolder(dir string) (string, string) {
	abs, err := filepath.Abs(strings.Trim(strings.TrimSpace(dir), `"'`))
	if err == nil {
		abs, err = filepath.EvalSymlinks(abs)
	}
	if err != nil {
		return "", "That folder was not found."
	}
	st, err := os.Stat(abs)
	if err != nil {
		return "", "That folder was not found."
	}
	if !st.IsDir() {
		return "", "That is a file, not a folder."
	}
	return abs, ""
}

func askFolder() string {
	exe, _ := os.Executable()
	def := filepath.Dir(exe)
	fmt.Println("  Which folder do you want to share?")
	fmt.Println("  Paste its path (in Explorer: click the address bar, Ctrl+C) and press Enter.")
	fmt.Println("  Just press Enter to share: " + def)
	fmt.Print("  > ")
	line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
	line = strings.TrimSpace(line)
	fmt.Println()
	if line == "" {
		return def
	}
	return line
}

func configDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = os.TempDir()
	}
	d = filepath.Join(d, "ShareFolder")
	os.MkdirAll(d, 0o700)
	return d
}

func loadPassword(set string) string {
	file := filepath.Join(configDir(), "password.txt")
	if set != "" {
		if len(set) < 8 {
			fatal("Please use a password of at least 8 characters.")
		}
		os.WriteFile(file, []byte(set), 0o600)
		return set
	}
	if b, err := os.ReadFile(file); err == nil && len(strings.TrimSpace(string(b))) >= 8 {
		return strings.TrimSpace(string(b))
	}
	p := friendlyPassword()
	os.WriteFile(file, []byte(p), 0o600)
	return p
}

func friendlyPassword() string {
	const letters = "abcdefghjkmnpqrstuvwxyz23456789" // no l, i, o, 0, 1
	b := make([]byte, 10)
	rand.Read(b)
	out := make([]byte, 10)
	for i := range b {
		out[i] = letters[int(b[i])%len(letters)]
	}
	return string(out[:5]) + "-" + string(out[5:])
}

// listen opens the port: only for this PC (the internet link reaches it from here) unless sharing on the WiFi too.
func listen(port int) (net.Listener, int) {
	host := "127.0.0.1"
	if lanMode {
		host = ""
	}
	for p := port; p < port+50; p++ {
		if ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", host, p)); err == nil {
			return ln, p
		}
	}
	ln, err := net.Listen("tcp", host+":0") // let Windows pick any free port
	if err != nil {
		fatal("No free port could be opened: " + err.Error())
	}
	return ln, ln.Addr().(*net.TCPAddr).Port
}

func lanIPs() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && !n.IP.IsLinkLocalUnicast() {
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}

func openBrowser(u string) {
	switch runtime.GOOS {
	case "windows":
		exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
	case "darwin":
		exec.Command("open", u).Start()
	default:
		exec.Command("xdg-open", u).Start()
	}
}

func fatal(msg string) {
	fmt.Println()
	fmt.Println("  Problem: " + msg)
	fmt.Println("  Press Enter to close.")
	bufio.NewReader(os.Stdin).ReadString('\n')
	os.Exit(1)
}

// cleanParts removes pieces of uploads that were never finished, after a day.
func cleanParts() {
	des, _ := os.ReadDir(filepath.Join(root, partsDir))
	for _, d := range des {
		if info, err := d.Info(); err == nil && time.Since(info.ModTime()) > 6*time.Hour {
			os.Remove(filepath.Join(root, partsDir, d.Name()))
			partLocks.Delete(strings.TrimSuffix(d.Name(), ".part"))
		}
	}
}

// ------------------------------------------------------------------ internet link

var tunnelURL = regexp.MustCompile(`https://[a-z0-9-]+\.trycloudflare\.com`)

func cloudflaredPath() (string, error) {
	name := "cloudflared"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	p := filepath.Join(configDir(), name) // only our own copy: never one found on PATH
	if st, err := os.Stat(p); err == nil && st.Size() > 1_000_000 {
		return p, nil
	}
	asset := map[string]string{
		"windows/amd64": "cloudflared-windows-amd64.exe", "windows/386": "cloudflared-windows-386.exe",
		"windows/arm64": "cloudflared-windows-amd64.exe", "linux/amd64": "cloudflared-linux-amd64",
		"linux/arm64": "cloudflared-linux-arm64", "darwin/amd64": "cloudflared-darwin-amd64.tgz",
	}[runtime.GOOS+"/"+runtime.GOARCH]
	if asset == "" || strings.HasSuffix(asset, ".tgz") {
		return "", errors.New("install cloudflared yourself on this system")
	}
	fmt.Println("  Getting the internet-link helper (cloudflared, about 50 MB, only the first time)...")
	client := &http.Client{Timeout: 15 * time.Minute}
	resp, err := client.Get("https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("download failed (%s)", resp.Status)
	}
	tmp := p + ".download"
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	pr := &progress{total: resp.ContentLength, last: time.Now()}
	if _, err := io.Copy(f, io.TeeReader(resp.Body, pr)); err != nil {
		f.Close()
		os.Remove(tmp)
		return "", err
	}
	f.Close()
	fmt.Println("  Done.")
	os.Chmod(tmp, 0o755)
	if err := os.Rename(tmp, p); err != nil {
		return "", err
	}
	return p, nil
}

type progress struct {
	total, done int64
	last        time.Time
}

func (p *progress) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if time.Since(p.last) > 3*time.Second && p.total > 0 {
		p.last = time.Now()
		fmt.Printf("  ... %d%%\n", p.done*100/p.total)
	}
	return len(b), nil
}

func startTunnel(port int) {
	wait := 3 * time.Second
	for {
		exe, err := cloudflaredPath()
		if err != nil {
			fmt.Println("  Internet link not available yet: " + err.Error())
			fmt.Println("  Trying again in a minute...")
			time.Sleep(time.Minute)
			continue
		}
		started := time.Now()
		cmd := exec.Command(exe, "tunnel", "--no-autoupdate", "--url", fmt.Sprintf("http://127.0.0.1:%d", port))
		out, _ := cmd.StderrPipe()
		cmd.Stdout = cmd.Stderr
		if err := cmd.Start(); err != nil {
			fmt.Println("  Internet link could not start: " + err.Error())
			return
		}
		fmt.Println("  Making the internet link...")
		sc := bufio.NewScanner(out)
		shown := false
		for sc.Scan() {
			if m := tunnelURL.FindString(sc.Text()); m != "" && m != "https://api.trycloudflare.com" && !shown {
				shown = true
				fmt.Println()
				fmt.Println("  ************************************************************")
				fmt.Println("   INTERNET LINK (send this + the password):")
				fmt.Println("   " + m)
				fmt.Println("  ************************************************************")
				fmt.Println()
			}
		}
		cmd.Wait()
		if shown || time.Since(started) > 2*time.Minute {
			wait = 3 * time.Second
			fmt.Println("  The internet link dropped; making a new one (the address will change)...")
		} else {
			fmt.Println("  No internet yet; trying again...")
		}
		time.Sleep(wait)
		if wait < time.Minute {
			wait *= 2
		}
	}
}

// ------------------------------------------------------------------ auth

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func clientIP(r *http.Request) string {
	h, _, _ := net.SplitHostPort(r.RemoteAddr)
	// only cloudflared (on this PC) may say who the visitor really is
	if ip := r.Header.Get("Cf-Connecting-Ip"); ip != "" && net.ParseIP(h).IsLoopback() {
		return ip
	}
	return h
}

func isHTTPS(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" || r.Header.Get("Cf-Visitor") != ""
}

func setSession(w http.ResponseWriter, r *http.Request) {
	tok := randHex(24)
	sessMu.Lock()
	sessions[tok] = time.Now().Add(30 * 24 * time.Hour)
	sessMu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: "sf", Value: tok, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode,
		Secure: isHTTPS(r), MaxAge: 30 * 24 * 3600})
}

func loggedIn(r *http.Request) bool {
	c, err := r.Cookie("sf")
	if err != nil {
		return false
	}
	sessMu.Lock()
	defer sessMu.Unlock()
	exp, ok := sessions[c.Value]
	if ok && time.Now().After(exp) {
		delete(sessions, c.Value)
		return false
	}
	return ok
}

func auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !loggedIn(r) {
			jsonErr(w, 401, "Password needed")
			return
		}
		// changes must come from the page itself (no other site can make a logged-in browser change files)
		if r.Method != http.MethodGet && r.Header.Get("X-ShareFolder") != "1" {
			jsonErr(w, 403, "Refused")
			return
		}
		h(w, r)
	}
}

func writer(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			jsonErr(w, 405, "Use POST")
			return
		}
		if readOnly {
			jsonErr(w, 403, "This folder is shared read-only")
			return
		}
		h(w, r)
	}
}

func apiLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		jsonErr(w, 405, "Use POST")
		return
	}
	ip := clientIP(r)
	failMu.Lock()
	recent := fails[ip][:0]
	for _, t := range fails[ip] {
		if time.Since(t) < 10*time.Minute {
			recent = append(recent, t)
		}
	}
	blocked := len(recent) >= 8 || globalFails.Load() >= 60
	if !blocked {
		recent = append(recent, time.Now()) // counted before the password is even read; taken back if it is right
	}
	fails[ip] = recent
	failMu.Unlock()
	if blocked {
		jsonErr(w, 429, "Too many wrong passwords. Wait 10 minutes.")
		return
	}
	var body struct{ Password, Key string }
	json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&body)
	ok := body.Password != "" && subtle.ConstantTimeCompare([]byte(strings.TrimSpace(body.Password)), []byte(password)) == 1
	if !ok && body.Key != "" {
		oneTimeMu.Lock()
		if oneTimeKey != "" && subtle.ConstantTimeCompare([]byte(body.Key), []byte(oneTimeKey)) == 1 {
			ok = true
			oneTimeKey = "" // works once
		}
		oneTimeMu.Unlock()
	}
	if !ok {
		globalFails.Add(1)
		time.AfterFunc(10*time.Minute, func() { globalFails.Add(-1) })
		time.Sleep(700 * time.Millisecond)
		jsonErr(w, 403, "Wrong password")
		return
	}
	failMu.Lock()
	if n := len(fails[ip]); n > 0 {
		fails[ip] = fails[ip][:n-1]
	}
	failMu.Unlock()
	setSession(w, r)
	writeJSON(w, map[string]any{"ok": true})
}

func apiLogout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie("sf"); err == nil {
		sessMu.Lock()
		delete(sessions, c.Value)
		sessMu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: "sf", Value: "", Path: "/", MaxAge: -1})
	writeJSON(w, map[string]any{"ok": true})
}

func apiWhoami(w http.ResponseWriter, r *http.Request) {
	name := filepath.Base(root)
	if name == string(filepath.Separator) || name == "." || strings.HasSuffix(name, ":") {
		name = "Drive " + strings.TrimSuffix(filepath.VolumeName(root), ":")
	}
	writeJSON(w, map[string]any{"ok": true, "readOnly": readOnly, "name": name})
}

// ------------------------------------------------------------------ paths

var badName = regexp.MustCompile(`[\\/:*?"<>|\x00-\x1f]`)
var reserved = regexp.MustCompile(`(?i)^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³]|conin\$|conout\$)(\..*)?$`)

// hidden: the program's own folders inside the share (in any letter case: Windows ignores case),
// and Windows' own system folders.
func hidden(name string) bool {
	l := strings.ToLower(name)
	return strings.HasPrefix(l, strings.ToLower(hiddenPrefix)) || l == "$recycle.bin" || l == "system volume information"
}

// blocked: an item nobody may see or touch: hidden names, links and junctions (they can lead out of the
// folder), Windows hidden/system items, this program and its settings, and the trash under any other name.
func blocked(name string, fi fs.FileInfo) bool {
	if hidden(name) || fi.Mode()&(fs.ModeSymlink|fs.ModeIrregular) != 0 || hiddenAttr(fi) {
		return true
	}
	for _, p := range protected {
		if os.SameFile(fi, p) {
			return true
		}
	}
	for _, d := range []string{trashDir, partsDir} {
		if st, err := os.Lstat(filepath.Join(root, d)); err == nil && os.SameFile(fi, st) {
			return true
		}
	}
	return false
}

// resolve turns a page path ("/a/b") into a path inside the shared folder, or fails. Every name may be
// spelled one way only, and no part of the way may be a link, a junction or a hidden item.
func resolve(p string) (string, error) {
	p = strings.ReplaceAll(p, "\\", "/")
	clean := path.Clean("/" + p)
	var segs []string
	for _, seg := range strings.Split(clean, "/") {
		if seg == "" {
			continue
		}
		if hidden(seg) || strings.ContainsAny(seg, ":*?\"<>|") || strings.HasSuffix(seg, ".") || strings.HasSuffix(seg, " ") || reserved.MatchString(seg) {
			return "", errors.New("not allowed")
		}
		segs = append(segs, seg)
	}
	full := filepath.Join(append([]string{root}, segs...)...)
	if !within(full) {
		return "", errors.New("not allowed")
	}
	cur := root
	for _, seg := range segs {
		cur = filepath.Join(cur, seg)
		fi, err := os.Lstat(cur)
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				break // the rest does not exist yet (a new folder or file): nothing to follow
			}
			return "", errors.New("not allowed")
		}
		if blocked(seg, fi) {
			return "", errors.New("not allowed")
		}
	}
	return full, nil
}

func within(full string) bool {
	rel, err := filepath.Rel(root, full)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func checkName(n string) (string, error) {
	n = strings.TrimSpace(n)
	n = strings.TrimRight(n, ". ")
	if n == "" || n == "." || n == ".." || len(n) > 200 || badName.MatchString(n) || reserved.MatchString(n) || hidden(n) || strings.HasSuffix(n, " ") {
		return "", errors.New("That name can't be used")
	}
	return n, nil
}

// unique gives "name", "name (2)", ... (or "name - Copy", "name - Copy (2)") that does not exist in dir.
func unique(dir, name string, copy bool) string {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	if ext == name {
		ext, stem = "", name
	}
	exists := func(n string) bool { _, err := os.Lstat(filepath.Join(dir, n)); return err == nil }
	if !copy && !exists(name) {
		return name
	}
	if copy {
		stem += " - Copy"
		if !exists(stem + ext) {
			return stem + ext
		}
	}
	for i := 2; ; i++ {
		n := fmt.Sprintf("%s (%d)%s", stem, i, ext)
		if !exists(n) {
			return n
		}
	}
}

func rel(full string) string {
	r, _ := filepath.Rel(root, full)
	if r == "." {
		return "/"
	}
	return "/" + filepath.ToSlash(r)
}

// ------------------------------------------------------------------ reading

type entry struct {
	Name  string `json:"name"`
	Dir   bool   `json:"dir"`
	Size  int64  `json:"size"`
	MTime int64  `json:"mtime"`
	Path  string `json:"path,omitempty"`
	Sub   bool   `json:"sub,omitempty"` // a folder that has folders inside (for the tree)
}

func entryOf(full string, d fs.DirEntry) (entry, bool) {
	info, err := d.Info()
	if err != nil || blocked(d.Name(), info) {
		return entry{}, false
	}
	e := entry{Name: d.Name(), Dir: info.IsDir(), Size: info.Size(), MTime: info.ModTime().UnixMilli()}
	if e.Dir {
		e.Size = 0
	}
	return e, true
}

func apiList(w http.ResponseWriter, r *http.Request) {
	full, err := resolve(r.URL.Query().Get("p"))
	if err != nil {
		jsonErr(w, 403, "Not allowed")
		return
	}
	des, err := os.ReadDir(full)
	if err != nil {
		jsonErr(w, 404, "This folder can't be opened")
		return
	}
	foldersOnly := r.URL.Query().Get("dirs") == "1"
	out := []entry{}
	for _, d := range des {
		if hidden(d.Name()) {
			continue
		}
		e, ok := entryOf(filepath.Join(full, d.Name()), d)
		if !ok || (foldersOnly && !e.Dir) {
			continue
		}
		if foldersOnly {
			if sub, err := os.ReadDir(filepath.Join(full, d.Name())); err == nil {
				for _, s := range sub {
					if si, err := s.Info(); err == nil && s.IsDir() && !blocked(s.Name(), si) {
						e.Sub = true
						break
					}
				}
			}
		}
		out = append(out, e)
	}
	writeJSON(w, map[string]any{"path": rel(full), "items": out})
}

func apiSearch(w http.ResponseWriter, r *http.Request) {
	full, err := resolve(r.URL.Query().Get("p"))
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if err != nil || q == "" {
		writeJSON(w, map[string]any{"items": []entry{}})
		return
	}
	out := []entry{}
	stop := errors.New("enough")
	start := time.Now()
	filepath.WalkDir(full, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if info, err := d.Info(); p != full && (err != nil || blocked(d.Name(), info)) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if p != full && strings.Contains(strings.ToLower(d.Name()), q) {
			if e, ok := entryOf(p, d); ok {
				e.Path = rel(filepath.Dir(p))
				out = append(out, e)
			}
		}
		if len(out) >= 500 || time.Since(start) > 8*time.Second {
			return stop
		}
		return nil
	})
	writeJSON(w, map[string]any{"items": out})
}

func contentDisposition(kind, name string) string {
	ascii := strings.Map(func(r rune) rune {
		if r < 32 || r > 126 || r == '"' || r == '\\' {
			return '_'
		}
		return r
	}, name)
	return fmt.Sprintf(`%s; filename="%s"; filename*=UTF-8''%s`, kind, ascii, url.PathEscape(name))
}

var inlineTypes = regexp.MustCompile(`^(image/(png|jpeg|gif|webp|bmp)|video/(mp4|webm|ogg)|audio/(mpeg|mp4|ogg|wav|webm|x-wav|aac)|application/pdf)$`)

func apiRaw(w http.ResponseWriter, r *http.Request) {
	full, err := resolve(r.URL.Query().Get("p"))
	if err != nil {
		http.Error(w, "Not allowed", 403)
		return
	}
	f, err := os.Open(full)
	if err != nil {
		http.Error(w, "Not found", 404)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.IsDir() {
		http.Error(w, "Not a file", 400)
		return
	}
	name := filepath.Base(full)
	ctype := mime.TypeByExtension(strings.ToLower(filepath.Ext(name)))
	if i := strings.Index(ctype, ";"); i > 0 {
		ctype = ctype[:i]
	}
	// a file is shown in the page only as a picture, video, sound, PDF or plain text; everything else downloads
	switch {
	case r.URL.Query().Get("dl") == "1":
		w.Header().Set("Content-Disposition", contentDisposition("attachment", name))
		w.Header().Set("Content-Type", "application/octet-stream")
	case inlineTypes.MatchString(ctype):
		w.Header().Set("Content-Disposition", contentDisposition("inline", name))
		w.Header().Set("Content-Type", ctype)
	case r.URL.Query().Get("text") == "1":
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	default:
		w.Header().Set("Content-Disposition", contentDisposition("attachment", name))
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	// nothing served from the folder can run as part of this site (a PDF is shown by the browser's own viewer)
	if ctype != "application/pdf" || r.URL.Query().Get("dl") == "1" {
		w.Header().Set("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self' data:; media-src 'self'; style-src 'unsafe-inline'")
	}
	w.Header().Set("Cache-Control", "private, max-age=60")
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func apiZip(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	var fulls []string
	for _, p := range q["p"] {
		full, err := resolve(p)
		if err != nil || full == root && len(q["p"]) > 1 {
			http.Error(w, "Not allowed", 403)
			return
		}
		fulls = append(fulls, full)
	}
	if len(fulls) == 0 {
		http.Error(w, "Nothing to download", 400)
		return
	}
	name := q.Get("name")
	if n, err := checkName(name); err != nil || !strings.HasSuffix(strings.ToLower(n), ".zip") {
		name = "Shared files.zip"
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", contentDisposition("attachment", name))
	zw := zip.NewWriter(w)
	buf := make([]byte, 1<<20)
	var skipped []string
	for _, top := range fulls {
		base := filepath.Dir(top)
		filepath.WalkDir(top, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if info, err := d.Info(); err != nil || (p != top || top != root) && blocked(d.Name(), info) {
				if d.IsDir() {
					return filepath.SkipDir
				}
				return nil
			}
			relp, _ := filepath.Rel(base, p)
			if top == root {
				relp, _ = filepath.Rel(root, p)
				if relp == "." {
					return nil
				}
			}
			relp = filepath.ToSlash(relp)
			info, err := d.Info()
			if err != nil {
				return nil
			}
			if d.IsDir() {
				zw.CreateHeader(&zip.FileHeader{Name: relp + "/", Modified: info.ModTime()})
				return nil
			}
			f, err := os.Open(p)
			if err != nil {
				skipped = append(skipped, relp) // open in another program
				return nil
			}
			defer f.Close()
			hw, err := zw.CreateHeader(&zip.FileHeader{Name: relp, Method: zip.Store, Modified: info.ModTime()})
			if err != nil {
				panic(http.ErrAbortHandler)
			}
			if _, err = io.CopyBuffer(hw, f, buf); err != nil {
				panic(http.ErrAbortHandler) // the browser shows the download as failed instead of a short ZIP
			}
			return nil
		})
	}
	if len(skipped) > 0 {
		if hw, err := zw.Create("ShareFolder - files that could not be added.txt"); err == nil {
			io.WriteString(hw, "These files were open in another program on the PC and are not in this ZIP:\r\n\r\n"+strings.Join(skipped, "\r\n")+"\r\n")
		}
	}
	zw.Close()
}

// ------------------------------------------------------------------ changing

func readBody(r *http.Request, v any) error {
	return json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(v)
}

func apiMkdir(w http.ResponseWriter, r *http.Request) {
	var b struct{ P, Name string }
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	dir, err := resolve(b.P)
	name, err2 := checkName(b.Name)
	if err != nil || err2 != nil {
		jsonErr(w, 400, "That name can't be used")
		return
	}
	name = unique(dir, name, false)
	if err := os.Mkdir(filepath.Join(dir, name), 0o755); err != nil {
		jsonErr(w, 500, "The folder could not be made")
		return
	}
	writeJSON(w, map[string]any{"name": name})
}

func apiRename(w http.ResponseWriter, r *http.Request) {
	var b struct{ P, Name string }
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	full, err := resolve(b.P)
	name, err2 := checkName(b.Name)
	if err != nil || err2 != nil || full == root {
		jsonErr(w, 400, "That name can't be used")
		return
	}
	if name == filepath.Base(full) {
		writeJSON(w, map[string]any{"name": name})
		return
	}
	dir := filepath.Dir(full)
	if !strings.EqualFold(name, filepath.Base(full)) {
		name = unique(dir, name, false)
	}
	if err := os.Rename(full, filepath.Join(dir, name)); err != nil {
		log.Println("rename:", err)
		jsonErr(w, 500, "It could not be renamed (is it open in another program?)")
		return
	}
	writeJSON(w, map[string]any{"name": name})
}

// apiDelete moves items into the hidden trash folder inside the shared folder, so nothing is lost by mistake.
func apiDelete(w http.ResponseWriter, r *http.Request) {
	var b struct{ Paths []string }
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	trash := filepath.Join(root, trashDir, time.Now().Format("2006-01-02 15.04.05"))
	failed := 0
	for _, p := range b.Paths {
		full, err := resolve(p)
		if err != nil || full == root {
			failed++
			continue
		}
		os.MkdirAll(trash, 0o755)
		if err := os.Rename(full, filepath.Join(trash, unique(trash, filepath.Base(full), false))); err != nil {
			failed++
		}
	}
	if failed > 0 {
		jsonErr(w, 500, fmt.Sprintf("%d item(s) could not be deleted (open in another program?)", failed))
		return
	}
	writeJSON(w, map[string]any{"ok": true})
}

func apiPaste(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Mode  string
		Paths []string
		Dest  string
	}
	if readBody(r, &b) != nil {
		jsonErr(w, 400, "Bad request")
		return
	}
	dest, err := resolve(b.Dest)
	if err != nil {
		jsonErr(w, 403, "Not allowed")
		return
	}
	if st, err := os.Stat(dest); err != nil || !st.IsDir() {
		jsonErr(w, 400, "That folder is gone")
		return
	}
	// a copy can take minutes: send a space every few seconds so the internet link does not give up (JSON ignores them)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(200)
	var wmu sync.Mutex
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				wmu.Lock()
				w.Write([]byte(" "))
				if f, ok := w.(http.Flusher); ok {
					f.Flush()
				}
				wmu.Unlock()
			}
		}
	}()
	answer := func(v map[string]any) {
		close(stop)
		wmu.Lock()
		json.NewEncoder(w).Encode(v)
		wmu.Unlock()
	}
	var names []string
	for _, p := range b.Paths {
		src, err := resolve(p)
		if err != nil || src == root {
			answer(map[string]any{"error": "Not allowed"})
			return
		}
		if _, err := os.Lstat(src); err != nil {
			answer(map[string]any{"error": "\"" + filepath.Base(src) + "\" is no longer there"})
			return
		}
		if insideOf(dest, src) {
			answer(map[string]any{"error": "A folder can't go inside itself"})
			return
		}
		same := sameFile(filepath.Dir(src), dest)
		if b.Mode == "cut" {
			if same {
				continue
			}
			n := unique(dest, filepath.Base(src), false)
			if err := os.Rename(src, filepath.Join(dest, n)); err != nil {
				log.Println("move:", err)
				answer(map[string]any{"error": "\"" + filepath.Base(src) + "\" could not be moved: it may be open in another program. Close it and try again."})
				return
			}
			names = append(names, n)
		} else {
			n := unique(dest, filepath.Base(src), same)
			if err := copyAll(src, filepath.Join(dest, n)); err != nil {
				log.Println("copy:", err)
				os.RemoveAll(filepath.Join(dest, n)) // no half copies
				answer(map[string]any{"error": "\"" + filepath.Base(src) + "\" could not be copied (a file may be open in another program, or the disk is full)."})
				return
			}
			names = append(names, n)
		}
	}
	answer(map[string]any{"names": names})
}

func sameFile(a, b string) bool {
	sa, err1 := os.Stat(a)
	sb, err2 := os.Stat(b)
	return err1 == nil && err2 == nil && os.SameFile(sa, sb)
}

// insideOf reports whether dest is src or inside it, by the files themselves (not how their names are spelled).
func insideOf(dest, src string) bool {
	ss, err := os.Stat(src)
	if err != nil || !ss.IsDir() {
		return false
	}
	for cur := dest; ; cur = filepath.Dir(cur) {
		if st, err := os.Stat(cur); err == nil && os.SameFile(st, ss) {
			return true
		}
		if !within(cur) || cur == root || filepath.Dir(cur) == cur {
			return false
		}
	}
}

func copyAll(src, dst string) error {
	st, err := os.Stat(src)
	if err != nil {
		return err
	}
	if !st.IsDir() {
		return copyFile(src, dst, st)
	}
	if err := os.MkdirAll(dst, 0o755); err != nil {
		return err
	}
	des, err := os.ReadDir(src)
	if err != nil {
		return err
	}
	for _, d := range des {
		if info, err := d.Info(); err != nil || blocked(d.Name(), info) {
			continue
		}
		if err := copyAll(filepath.Join(src, d.Name()), filepath.Join(dst, d.Name())); err != nil {
			return err
		}
	}
	return nil
}

func copyFile(src, dst string, st fs.FileInfo) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.CopyBuffer(out, in, make([]byte, 1<<20)); err != nil {
		out.Close()
		os.Remove(dst)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	os.Chtimes(dst, time.Now(), st.ModTime())
	return nil
}

// apiUpload receives a file in pieces (each under the internet link's size limit) and moves it into place when complete.
//
//	POST /api/upload?dest=/a&rel=sub/name.ext&id=<upload id>&offset=N&total=T&mtime=ms   body = the piece
func apiUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if _, err := resolve(q.Get("dest")); err != nil {
		jsonErr(w, 403, "Not allowed")
		return
	}
	id := q.Get("id")
	if !regexp.MustCompile(`^[a-zA-Z0-9]{8,64}$`).MatchString(id) {
		jsonErr(w, 400, "Bad upload id")
		return
	}
	if name, ok := finished.Load(id); ok {
		writeJSON(w, map[string]any{"done": true, "name": name, "have": q.Get("total")})
		return
	}
	offset, err1 := strconv.ParseInt(q.Get("offset"), 10, 64)
	total, err2 := strconv.ParseInt(q.Get("total"), 10, 64)
	if err1 != nil || err2 != nil || offset < 0 || total < 0 || offset > total {
		jsonErr(w, 400, "Bad upload position")
		return
	}
	// every folder in the relative path, and the file name, must be valid names
	parts := strings.Split(strings.ReplaceAll(q.Get("rel"), "\\", "/"), "/")
	var clean []string
	for _, s := range parts {
		if s == "" {
			continue
		}
		n, err := checkName(s)
		if err != nil {
			jsonErr(w, 400, "The name \""+s+"\" can't be used")
			return
		}
		clean = append(clean, n)
	}
	if len(clean) == 0 {
		jsonErr(w, 400, "No file name")
		return
	}
	pdir := filepath.Join(root, partsDir)
	os.MkdirAll(pdir, 0o755)
	part := filepath.Join(pdir, id+".part")

	l := lockFor(id)
	l.Lock()
	defer l.Unlock()
	f, err := os.OpenFile(part, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		log.Println("upload:", err)
		jsonErr(w, 500, "The file can't be saved here")
		return
	}
	st, _ := f.Stat()
	if st.Size() != offset {
		f.Close()
		writeJSONStatus(w, 409, map[string]any{"error": "out of order", "have": st.Size()})
		return
	}
	f.Seek(offset, io.SeekStart)
	n, err := io.CopyBuffer(f, io.LimitReader(r.Body, min(total-offset, 64<<20)), make([]byte, 1<<20))
	cerr := f.Close()
	if err != nil || cerr != nil {
		jsonErr(w, 500, "The piece was not saved (disk full?)")
		return
	}
	if offset+n < total {
		writeJSON(w, map[string]any{"have": offset + n})
		return
	}
	// complete: make the folders and move the file into place
	dirRel := q.Get("dest")
	for _, s := range clean[:len(clean)-1] {
		dirRel += "/" + s
	}
	dir, err := resolve(dirRel) // the folders on the way may not be links, junctions or hidden items
	if err != nil {
		os.Remove(part)
		jsonErr(w, 403, "Not allowed")
		return
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		os.Remove(part)
		jsonErr(w, 500, "The folder could not be made")
		return
	}
	name := unique(dir, clean[len(clean)-1], false)
	if err := os.Rename(part, filepath.Join(dir, name)); err != nil {
		log.Println("upload:", err)
		jsonErr(w, 500, "The file could not be saved")
		return
	}
	partLocks.Delete(id)
	finished.Store(id, name)
	time.AfterFunc(time.Hour, func() { finished.Delete(id) })
	if ms, err := strconv.ParseInt(q.Get("mtime"), 10, 64); err == nil && ms > 0 {
		t := time.UnixMilli(ms)
		os.Chtimes(filepath.Join(dir, name), time.Now(), t)
	}
	writeJSON(w, map[string]any{"done": true, "name": name, "have": total})
}

// ------------------------------------------------------------------ http plumbing

func serveIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	w.Write(indexHTML)
}

func allowedHost(h string) bool {
	if host, _, err := net.SplitHostPort(h); err == nil {
		h = host
	}
	h = strings.ToLower(strings.Trim(h, "[]"))
	return h == "localhost" || net.ParseIP(h) != nil || strings.HasSuffix(h, ".trycloudflare.com")
}

func securityHeaders(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowedHost(r.Host) {
			http.Error(w, "Unknown address", 421)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "SAMEORIGIN")
		h.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, v any) { writeJSONStatus(w, 200, v) }

func writeJSONStatus(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

func jsonErr(w http.ResponseWriter, code int, msg string) {
	writeJSONStatus(w, code, map[string]any{"error": msg})
}

func init() {
	log.SetFlags(0)
	mime.AddExtensionType(".mp4", "video/mp4")
	mime.AddExtensionType(".webm", "video/webm")
	mime.AddExtensionType(".mp3", "audio/mpeg")
	mime.AddExtensionType(".m4a", "audio/mp4")
	mime.AddExtensionType(".wav", "audio/wav")
	mime.AddExtensionType(".pdf", "application/pdf")
	mime.AddExtensionType(".webp", "image/webp")
}
