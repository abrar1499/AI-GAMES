package main

// Short and permanent links.
//
// Free: the quick-tunnel address (….trycloudflare.com) is sent to several short-link
// services in turn, shortest first; a short link is used only after checking that it
// really leads to the folder (some services refuse tunnel addresses or show a warning page).
//
// Permanent: with a domain on a free Cloudflare account, the owner connects Cloudflare once
// ("cloudflared tunnel login" in the browser), types an address such as files.example.com,
// and ShareFolder makes a named tunnel and its DNS record. That address then stays the same.

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// ------------------------------------------------------------------ free short links

type shortener struct {
	name, host string
	make       func(c *http.Client, long string) (*http.Response, error)
}

func getURL(api string) func(c *http.Client, long string) (*http.Response, error) {
	return func(c *http.Client, long string) (*http.Response, error) { return c.Get(api + url.QueryEscape(long)) }
}

func postForm(api string) func(c *http.Client, long string) (*http.Response, error) {
	return func(c *http.Client, long string) (*http.Response, error) {
		req, _ := http.NewRequest("POST", api, strings.NewReader("url="+url.QueryEscape(long)))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Accept", "application/json")
		return c.Do(req)
	}
}

var shorteners = []shortener{
	{"da.gd", "da.gd", getURL("https://da.gd/s?url=")},
	{"is.gd", "is.gd", getURL("https://is.gd/create.php?format=simple&url=")},
	{"v.gd", "v.gd", getURL("https://v.gd/create.php?format=simple&url=")},
	{"clck.ru", "clck.ru", getURL("https://clck.ru/--?url=")},
	{"spoo.me", "spoo.me", postForm("https://spoo.me/")},
	{"TinyURL", "tinyurl.com", getURL("https://tinyurl.com/api-create.php?url=")},
	{"CleanURI", "cleanuri.com", postForm("https://cleanuri.com/api/v1/shorten")},
	{"ulvis", "ulvis.net", getURL("https://ulvis.net/api.php?private=1&url=")},
}

var shortTransport http.RoundTripper // nil: the normal one (tests put their own here)

// shorten finds a short link that leads to long, or returns "".
func shorten(long string) string {
	c := &http.Client{Timeout: 12 * time.Second, Transport: shortTransport}
	noFollow := &http.Client{Timeout: 12 * time.Second, Transport: shortTransport,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	for round := 0; round < 2; round++ {
		for _, s := range shorteners {
			resp, err := s.make(c, long)
			if err != nil {
				log.Printf("short link: %s: %v", s.name, err)
				continue
			}
			body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
			resp.Body.Close()
			text := strings.ReplaceAll(string(body), `\/`, "/") // JSON answers escape slashes
			m := regexp.MustCompile(`https?://(www\.)?` + regexp.QuoteMeta(s.host) + `/[A-Za-z0-9_-]{2,40}`).FindString(text)
			if m == "" {
				log.Printf("short link: %s refused (%d): %.120s", s.name, resp.StatusCode, strings.TrimSpace(string(body)))
				continue
			}
			m = strings.Replace(m, "http://", "https://", 1)
			if leadsTo(noFollow, m, long) {
				log.Printf("short link: %s gave %s", s.name, m)
				return m
			}
			log.Printf("short link: %s gave %s, but it does not lead straight to the folder", s.name, m)
		}
		time.Sleep(10 * time.Second)
	}
	return ""
}

// leadsTo checks that the short link answers with a redirect to the long one (not a warning page).
func leadsTo(c *http.Client, short, long string) bool {
	resp, err := c.Get(short)
	if err != nil {
		return false
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	return resp.StatusCode >= 300 && resp.StatusCode < 400 && strings.HasPrefix(strings.TrimSuffix(loc, "/"), strings.TrimSuffix(long, "/"))
}

// ------------------------------------------------------------------ permanent address

var hostnameRe = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$`)

var cf struct {
	mu       sync.Mutex
	busy     bool
	step     string // what is happening now, for the page
	err      string
	conflict string // an address that already has a DNS record, which the owner may choose to replace
	loginURL string
	login    *exec.Cmd
}

func cfState() map[string]any {
	cf.mu.Lock()
	defer cf.mu.Unlock()
	st := loadSettings()
	return map[string]any{"domain": st.Domain, "loggedIn": certExists(), "busy": cf.busy, "step": cf.step,
		"error": cf.err, "conflict": cf.conflict, "loginURL": cf.loginURL}
}

func cfSet(step, errText string) {
	cf.mu.Lock()
	cf.step, cf.err = step, errText
	cf.mu.Unlock()
	if step != "" {
		log.Println("cloudflare:", step)
	}
	if errText != "" {
		log.Println("cloudflare problem:", errText)
	}
}

// cfProblem is the tunnel's own news about the own address; it never overwrites what a setup step is saying.
func cfProblem(errText string) {
	cf.mu.Lock()
	defer cf.mu.Unlock()
	if cf.busy || cf.err == errText {
		return
	}
	cf.err = errText
	if errText != "" {
		cf.step = ""
		log.Println("cloudflare problem:", errText)
	}
}

// cloudflared keeps its Cloudflare login (cert.pem) and tunnel keys in a folder of its own inside
// ShareFolder's settings folder: never shown to visitors, and apart from any cloudflared setup the owner
// already has (whose config.yml could otherwise send the address somewhere else).
func cfHome() string {
	d := filepath.Join(configDir(), "cloudflare")
	os.MkdirAll(filepath.Join(d, ".cloudflared"), 0o700)
	return d
}

func cloudflaredHome() string { return filepath.Join(cfHome(), ".cloudflared") }

// cfCommand prepares a cloudflared command: no console window, cloudflared's home in cfHome, and none of
// the TUNNEL_... settings another cloudflared setup on this PC may have (they would override this one's).
func cfCommand(cmd *exec.Cmd) {
	noConsole(cmd)
	var env []string
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(strings.ToUpper(kv), "TUNNEL_") && !strings.HasPrefix(strings.ToUpper(kv), "HOME=") {
			env = append(env, kv)
		}
	}
	cmd.Env = append(env, "HOME="+cfHome())
}

// migrateCloudflare moves an own address set up by an earlier ShareFolder, which left cloudflared's
// login and tunnel key in the user's .cloudflared folder, into ShareFolder's own folder.
func migrateCloudflare(tunnelID string) {
	if tunnelID == "" || tunnelKey(tunnelID) {
		return
	}
	h, err := os.UserHomeDir()
	if err != nil {
		return
	}
	old := filepath.Join(h, ".cloudflared")
	for _, name := range []string{tunnelID + ".json", "cert.pem"} {
		dst := filepath.Join(cloudflaredHome(), name)
		if _, err := os.Stat(dst); err == nil {
			continue
		}
		if b, err := os.ReadFile(filepath.Join(old, name)); err == nil && os.WriteFile(dst, b, 0o600) == nil {
			log.Println("moved", name, "from", old)
			if name != "cert.pem" { // the login may also serve another cloudflared setup: it stays where it was
				os.Remove(filepath.Join(old, name))
			}
		}
	}
}

func certExists() bool {
	st, err := os.Stat(filepath.Join(cloudflaredHome(), "cert.pem"))
	return err == nil && st.Size() > 0
}

// cfRun runs one cloudflared command; it gives back what it printed as its answer, and everything it printed.
func cfRun(args ...string) (string, string, error) {
	exe, err := cloudflaredPath()
	if err != nil {
		return "", "", err
	}
	cmd := exec.Command(exe, args...)
	cfCommand(cmd)
	var stdout, all strings.Builder
	cmd.Stdout = io.MultiWriter(&stdout, &all)
	cmd.Stderr = &all
	if err = cmd.Start(); err == nil {
		bindChild(cmd)
		err = cmd.Wait()
	}
	log.Printf("cloudflared %s:\n%s", strings.Join(args, " "), all.String())
	return stdout.String(), all.String(), err
}

var loginURLRe = regexp.MustCompile(`https://dash\.cloudflare\.com/argotunnel\S*`)

// cfLogin connects ShareFolder to the owner's Cloudflare account: cloudflared opens a Cloudflare page in the
// browser, the owner picks the domain and presses Authorize, and cloudflared saves cert.pem.
// With again, the saved connection is put aside first (to choose a different domain or account).
func cfLogin(again bool) {
	if !cfStart() {
		return
	}
	defer cfDone()
	cert := filepath.Join(cloudflaredHome(), "cert.pem")
	if again && certExists() {
		os.Rename(cert, filepath.Join(cloudflaredHome(), "cert-"+time.Now().Format("20060102-150405")+".pem.old"))
		// the tunnel and address belong to the old connection
		had := loadSettings().Domain != ""
		changeSettings(func(s *settings) { s.Domain, s.TunnelID = "", "" })
		setOwnHost("")
		if had {
			restartTunnel()
		}
	}
	if certExists() {
		cfSet("Cloudflare is already connected. Now type the address you want.", "")
		return
	}
	cfSet("Getting the Cloudflare helper…", "")
	exe, err := cloudflaredPath()
	if err != nil {
		cfSet("", "The Cloudflare helper could not be downloaded: "+err.Error())
		return
	}
	cfSet("Opening Cloudflare in your browser…", "")
	cmd := exec.Command(exe, "tunnel", "login")
	cfCommand(cmd)
	out, _ := cmd.StderrPipe()
	cmd.Stdout = cmd.Stderr
	if err := cmd.Start(); err != nil {
		cfSet("", "The Cloudflare helper could not start.")
		return
	}
	bindChild(cmd)
	cf.mu.Lock()
	cf.login = cmd
	cf.mu.Unlock()
	done := make(chan struct{})
	go func() {
		sc := bufio.NewScanner(out)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		notOpened := false
		for sc.Scan() {
			line := sc.Text()
			log.Println("cloudflared login:", line)
			if strings.Contains(line, "Please open the following URL") {
				notOpened = true // cloudflared could not open the browser: this program does it
			}
			if u := loginURLRe.FindString(line); u != "" {
				cf.mu.Lock()
				cf.loginURL = u
				cf.mu.Unlock()
				if notOpened {
					openBrowser(u)
				}
				cfSet("A Cloudflare page opened in your browser: log in, click your domain, then press Authorize. Then come back here.", "")
			}
		}
		close(done)
	}()
	waited := make(chan struct{})
	go func() { <-done; cmd.Wait(); close(waited) }()
	select {
	case <-waited:
	case <-time.After(15 * time.Minute):
		cmd.Process.Kill()
		<-waited
	}
	cf.mu.Lock()
	cf.loginURL, cf.login = "", nil
	cf.mu.Unlock()
	if certExists() {
		cfSet("Cloudflare is connected. Now type the address you want and press Use this address.", "")
	} else {
		cfSet("", "Cloudflare was not connected. Press Connect Cloudflare again and finish in the browser: choose your domain, then press Authorize.")
	}
}

// cfQuit stops a Cloudflare login that is still waiting (when sharing stops).
func cfQuit() {
	cf.mu.Lock()
	defer cf.mu.Unlock()
	if cf.login != nil && cf.login.Process != nil {
		cf.login.Process.Kill()
	}
}

func cfStart() bool {
	cf.mu.Lock()
	defer cf.mu.Unlock()
	if cf.busy {
		return false
	}
	cf.busy, cf.err, cf.conflict = true, "", ""
	return true
}

func cfDone() { cf.mu.Lock(); cf.busy = false; cf.mu.Unlock() }

type cfTunnel struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	DeletedAt string `json:"deleted_at"`
}

func (t cfTunnel) alive() bool {
	return t.DeletedAt == "" || strings.HasPrefix(t.DeletedAt, "0001")
}

var uuidRe = regexp.MustCompile(`[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`)

// tunnelKey is the file that lets this PC run a tunnel; it is written by "cloudflared tunnel create".
func tunnelKey(id string) bool {
	_, err := os.Stat(filepath.Join(cloudflaredHome(), id+".json"))
	return uuidRe.MatchString(id) && err == nil
}

var cnameRe = regexp.MustCompile(`(?:Added CNAME (\S+) which will route|(\S+) is already configured to route|(\S+) updated to route)`)

// cfUseDomain makes (or reuses) this PC's tunnel, points host at it and switches to it. An address that
// already has a DNS record (a website, mail) is only taken over when the owner said so (replace).
func cfUseDomain(host string, replace bool) {
	if !cfStart() {
		return
	}
	defer cfDone()
	if !certExists() {
		cfSet("", "Connect Cloudflare first (step 2).")
		return
	}
	cfSet("Making your tunnel at Cloudflare…", "")
	id := ""
	saved := loadSettings().TunnelID
	answer, _, err := cfRun("tunnel", "list", "--output", "json")
	var ts []cfTunnel
	if err == nil && json.Unmarshal([]byte(answer), &ts) == nil {
		// only a tunnel of the account connected now, which this PC has the key of
		for _, t := range ts {
			if t.alive() && t.ID == saved && tunnelKey(t.ID) {
				id = t.ID
			}
		}
		for _, t := range ts {
			if id == "" && t.alive() && strings.HasPrefix(t.Name, "sharefolder") && tunnelKey(t.ID) {
				id = t.ID
			}
		}
	} else if tunnelKey(saved) {
		id = saved
	}
	if id == "" {
		answer, all, err := cfRun("tunnel", "create", "sharefolder")
		if err != nil && strings.Contains(strings.ToLower(all), "already exist") {
			// that name was made on another PC, or its key file is gone: this PC gets its own
			answer, all, err = cfRun("tunnel", "create", "sharefolder-"+randHex(3))
		}
		if err != nil {
			cfSet("", "Cloudflare did not make the tunnel. "+lastLine(all))
			return
		}
		if i := strings.Index(answer, "Created tunnel"); i >= 0 {
			id = uuidRe.FindString(answer[i:])
		}
		if !tunnelKey(id) {
			cfSet("", "Cloudflare's answer could not be read. "+lastLine(all))
			return
		}
	}
	cfSet("Pointing "+host+" to this PC…", "")
	args := []string{"tunnel", "route", "dns"}
	if replace {
		args = append(args, "--overwrite-dns")
	}
	_, all, err := cfRun(append(args, id, host)...)
	if err != nil {
		low := strings.ToLower(all)
		switch {
		case strings.Contains(low, "already exists"):
			cf.mu.Lock()
			cf.conflict = host
			cf.mu.Unlock()
			cfSet("", host+" is already used in your Cloudflare DNS (maybe by your website or email). Type another name, such as "+otherName(host)+", or press Replace it to point "+host+" to this PC instead.")
		case strings.Contains(low, "zone") || strings.Contains(low, "not found"):
			cfSet("", "Cloudflare could not set up "+host+". Is it on the domain you chose when connecting Cloudflare? "+lastLine(all))
		default:
			cfSet("", "Cloudflare could not set up "+host+". "+lastLine(all))
		}
		return
	}
	// Cloudflare puts the name inside the domain chosen at login: "files.other.com" on "example.com"
	// becomes "files.other.com.example.com", which is not what the owner typed.
	if m := cnameRe.FindStringSubmatch(all); m != nil {
		made := strings.TrimSuffix(strings.ToLower(m[1]+m[2]+m[3]), ".")
		if made != host {
			cfSet("", host+" is not on the domain you connected: Cloudflare made "+made+" instead (you can delete that record on your domain's DNS page). Type an address that ends with your connected domain, or press Use a different domain.")
			return
		}
	}
	changeSettings(func(s *settings) { s.Domain, s.TunnelID = host, id })
	setOwnHost(host)
	msg := "Done. Your permanent link is https://" + host + " (the first time it can take a few minutes to start working)."
	if len(pw()) < 10 {
		// this link never changes, so strangers have all the time in the world to try passwords
		if setPassword(newLongPassword()) == nil {
			msg += " Because this link never changes, the password is now longer. Send the link and the new password."
		}
	}
	cfSet(msg, "")
	restartTunnel()
}

// cfStop goes back to the free link that changes each time. It waits for nothing: false when a
// Cloudflare step is still working.
func cfStop() bool {
	if !cfStart() {
		return false
	}
	defer cfDone()
	changeSettings(func(s *settings) { s.Domain = "" }) // the tunnel is kept, to be used again
	setOwnHost("")
	cfSet("", "")
	restartTunnel()
	return true
}

// otherName suggests a free-looking address next to host: files.example.com for example.com.
func otherName(host string) string {
	if strings.HasPrefix(host, "files.") {
		return "share." + strings.TrimPrefix(host, "files.")
	}
	if parts := strings.SplitN(host, ".", 2); len(parts) == 2 && strings.Count(host, ".") > 1 {
		return "files." + parts[1]
	}
	return "files." + host
}

func lastLine(out string) string {
	lines := strings.Split(strings.TrimSpace(out), "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		l := strings.TrimSpace(lines[i])
		if l != "" {
			if len(l) > 200 {
				l = l[:200]
			}
			return "(" + l + ")"
		}
	}
	return ""
}

var restartNow = make(chan struct{}, 1)

// restartTunnel stops the running tunnel; startTunnel then starts the right kind again.
func restartTunnel() {
	select {
	case restartNow <- struct{}{}:
	default:
	}
	stMu.Lock()
	tunnelGen++
	if tunnelCmd != nil && tunnelCmd.Process != nil {
		tunnelCmd.Process.Kill()
	}
	status.Link, status.Short, status.ShortNote, status.Permanent = "", "", "", false
	stMu.Unlock()
	setStatus("Changing the link…", true)
}

func validHost(h string) (string, error) {
	h = strings.ToLower(strings.TrimSpace(h))
	h = strings.TrimPrefix(strings.TrimPrefix(h, "https://"), "http://")
	h = strings.TrimSuffix(h, "/")
	if !hostnameRe.MatchString(h) || strings.HasSuffix(h, ".trycloudflare.com") {
		return "", errors.New("Type an address like files.example.com (a domain you added to Cloudflare).")
	}
	return h, nil
}
