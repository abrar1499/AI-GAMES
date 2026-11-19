package main

// A link that never changes, without a domain: Tailscale Funnel. With the free Tailscale app installed and
// signed in (with Google, for example), "tailscale funnel --bg PORT" publishes this PC's share page at
// https://<pc-name>.<tailnet>.ts.net, which stays the same. ShareFolder also asks spoo.me once for a short
// name pointing there; as that address never changes, the short link never needs updating either.

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var ts struct {
	mu       sync.Mutex
	busy     bool
	step     string
	err      string
	loginURL string
}

var tsHostP atomic.Pointer[string] // the ts.net name, allowed as a Host

func tsHost() string {
	if p := tsHostP.Load(); p != nil {
		return *p
	}
	return ""
}

func setTsHost(h string) { tsHostP.Store(&h) }

func tsSet(step, errText string) {
	ts.mu.Lock()
	ts.step, ts.err = step, errText
	ts.mu.Unlock()
	if step != "" {
		log.Println("tailscale:", step)
	}
	if errText != "" {
		log.Println("tailscale problem:", errText)
	}
}

// tailscaleExe finds the Tailscale command-line program, or "".
func tailscaleExe() string {
	for _, d := range []string{os.Getenv("ProgramFiles"), os.Getenv("ProgramFiles(x86)")} {
		if d != "" {
			p := filepath.Join(d, "Tailscale", "tailscale.exe")
			if _, err := os.Stat(p); err == nil {
				return p
			}
		}
	}
	if p, err := exec.LookPath("tailscale"); err == nil {
		return p
	}
	return ""
}

func tsState() map[string]any {
	ts.mu.Lock()
	defer ts.mu.Unlock()
	st := loadSettings()
	return map[string]any{"installed": tailscaleExe() != "", "busy": ts.busy, "step": ts.step, "error": ts.err,
		"loginURL": ts.loginURL, "host": st.TsHost, "short": st.TsShort}
}

var tsLoginRe = regexp.MustCompile(`https://login\.tailscale\.com/\S+`)

// tsRun runs a tailscale command, showing any Tailscale page it asks the owner to open, and gives back what it printed.
func tsRun(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, tailscaleExe(), args...)
	noConsole(cmd)
	out, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return "", err
	}
	var all strings.Builder
	opened := false
	sc := bufio.NewScanner(out)
	for sc.Scan() {
		line := sc.Text()
		all.WriteString(line + "\n")
		if u := tsLoginRe.FindString(line); u != "" && !opened {
			opened = true
			ts.mu.Lock()
			ts.loginURL = u
			ts.mu.Unlock()
			openBrowser(u)
			tsSet("A Tailscale page opened in your browser: sign in or press Enable there, then come back here.", "")
		}
	}
	err := cmd.Wait()
	log.Printf("tailscale %s:\n%s", strings.Join(args, " "), all.String())
	return all.String(), err
}

type tsStatus struct {
	BackendState string
	Self         struct{ DNSName string }
}

func tsStatusNow() tsStatus {
	var s tsStatus
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, tailscaleExe(), "status", "--json")
	noConsole(cmd)
	if b, err := cmd.Output(); err == nil {
		json.Unmarshal(b, &s)
	}
	return s
}

// tsStart signs in to Tailscale if needed, turns Funnel on for the share page and remembers the address.
func tsStart() {
	ts.mu.Lock()
	if ts.busy {
		ts.mu.Unlock()
		return
	}
	ts.busy, ts.err, ts.loginURL = true, "", ""
	ts.mu.Unlock()
	defer func() { ts.mu.Lock(); ts.busy, ts.loginURL = false, ""; ts.mu.Unlock() }()
	if tailscaleExe() == "" {
		tsSet("", "Tailscale is not installed yet. Press Get Tailscale, install it, sign in, then press Use Tailscale again.")
		return
	}
	tsSet("Checking Tailscale…", "")
	if s := tsStatusNow(); s.BackendState != "Running" {
		tsSet("Signing in to Tailscale…", "")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		tsRun(ctx, "up")
		cancel()
		if s := tsStatusNow(); s.BackendState != "Running" {
			tsSet("", "Tailscale is not signed in. Open the Tailscale app (in the corner of the taskbar), sign in, then press Use Tailscale again.")
			return
		}
	}
	tsSet("Turning on your permanent link…", "")
	var out string
	for try := 0; try < 2; try++ { // a second time after the owner enabled Funnel in the browser
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		out, _ = tsRun(ctx, "funnel", "--bg", itoa(sharePort))
		cancel()
		if strings.Contains(out, "Available on the internet") || strings.Contains(out, "started and running in the background") {
			break
		}
	}
	host := strings.TrimSuffix(tsStatusNow().Self.DNSName, ".")
	if host == "" || !(strings.Contains(out, "Available on the internet") || strings.Contains(out, "running in the background")) {
		tsSet("", "Tailscale did not turn the link on. If a Tailscale page asked you to enable Funnel or HTTPS, enable it there and press Use Tailscale again. "+lastLine(out))
		return
	}
	setTsHost(host)
	link := "https://" + host
	tsSet("Your permanent link is "+link+". Making a short name for it…", "")
	short := loadSettings().TsShort
	if short == "" || !leadsTo(&http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}, short, link) {
		short = shortAlias(link, strings.SplitN(host, ".", 2)[0])
	}
	changeSettings(func(s *settings) { s.TsHost, s.TsShort = host, short })
	msg := "Done. Your permanent link is " + link
	if short != "" {
		msg = "Done. Your permanent link is " + strings.TrimPrefix(short, "https://") + " (it opens " + link + ")"
	}
	tsSet(msg+". The first time, the link can take a few minutes to start working.", "")
	restartTunnel()
}

// shortAlias asks spoo.me for a short name such as spoo.me/abrar-pc-7k2 that leads to link.
func shortAlias(link, name string) string {
	name = strings.ToLower(regexp.MustCompile(`[^a-zA-Z0-9]+`).ReplaceAllString(name, ""))
	if len(name) > 12 {
		name = name[:12]
	}
	if name == "" {
		name = "files"
	}
	noFollow := &http.Client{Timeout: 12 * time.Second, Transport: shortTransport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	c := &http.Client{Timeout: 12 * time.Second, Transport: shortTransport}
	for i := 0; i < 4; i++ {
		alias := name + "-" + randHex(2)
		req, _ := http.NewRequest("POST", "https://spoo.me/", strings.NewReader(url.Values{"url": {link}, "alias": {alias}}.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Accept", "application/json")
		resp, err := c.Do(req)
		if err != nil {
			continue
		}
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		resp.Body.Close()
		m := regexp.MustCompile(`https?://spoo\.me/[A-Za-z0-9_-]+`).FindString(strings.ReplaceAll(string(body), `\/`, "/"))
		if m != "" && leadsTo(noFollow, m, link) {
			return strings.Replace(m, "http://", "https://", 1)
		}
	}
	return ""
}

// tsStop turns Funnel off and goes back to the free link.
func tsStop() {
	if tailscaleExe() != "" {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		tsRun(ctx, "funnel", "--https=443", "off")
		cancel()
	}
	changeSettings(func(s *settings) { s.TsHost = "" }) // the short name is kept: it still fits the same address later
	setTsHost("")
	tsSet("", "")
	restartTunnel()
}

// tsServe keeps the share page published while Tailscale is the link; it gives back false when Tailscale
// is not the link (any more).
func tsServe() bool {
	st := loadSettings()
	if st.TsHost == "" || tailscaleExe() == "" {
		return false
	}
	setTsHost(st.TsHost)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	out, _ := tsRun(ctx, "funnel", "--bg", itoa(sharePort)) // the port may differ from last time
	cancel()
	link := "https://" + st.TsHost
	stMu.Lock()
	status.Link, status.Short, status.ShortNote, status.Permanent = link, st.TsShort, "", true
	stMu.Unlock()
	if strings.Contains(out, "Available on the internet") || strings.Contains(out, "running in the background") {
		setStatus("Shared. Send the link and the password.", false)
	} else {
		setStatus("Tailscale is not ready (is the Tailscale app signed in?). Your link will work once it is.", false)
	}
	return true
}

func itoa(n int) string { return strconv.Itoa(n) }
