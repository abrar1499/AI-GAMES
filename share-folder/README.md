# ShareFolder

Share one folder of your Windows PC over the internet, behind a password, in a page that works like File Explorer: browse, search, upload (files and whole folders), download (a file, or several files and folders as one ZIP), new folder, rename, delete, cut, copy and paste, drag and drop, Ctrl+C / Ctrl+X / Ctrl+V, right-click menu, previews of pictures, videos, sound, PDFs and text.

The files stay on your PC. People who have the link and the password open them straight from it, so there is no storage limit and nothing is uploaded anywhere else.

## Use it

1. Download `dist/ShareFolder.exe`.
2. Double-click it. Windows may say "Windows protected your PC" because the program is not signed: click **More info**, then **Run anyway**.
3. A page opens in your browser:
   - Click **Choose folder**: the Windows folder window opens. Click the folder and press **Share this folder**. (If it doesn't show up, the page has **I can't see the window** and **Choose from a list instead**.)
   - Wait a few seconds for **your link**, with a QR code and a 6-digit **password**. ShareFolder tries several free short-link websites (da.gd, is.gd, v.gd, clck.ru, spoo.me, TinyURL, CleanURI, ulvis) and shows a short link only after checking that it really leads to your folder.
   - Send them with **Copy link + password** or the **WhatsApp** button. On a phone, people can also just scan the QR code.
4. The people you send it to open the link in any browser (phone too) and type the password.
5. Keep the PC on while they use it. Closing the browser tab does not stop sharing: use **Stop sharing** on that page. Double-click ShareFolder again to bring the page back.

The page also lets you:
- turn **People can add and change files** off (then they can only look and download);
- make a **new password**, or set your own;
- **open the folder page** yourself.

The folder and the settings are remembered for next time. The free link is new every time the program starts.

## A link that never changes

**Easiest (free, no domain): Tailscale.** Install the free Tailscale app from tailscale.com/download and sign in (with Google, for example). In ShareFolder click **Get a link that never changes**, then **Use Tailscale**. If a Tailscale page asks to enable Funnel or HTTPS, press Enable. Your link becomes `https://<pc-name>.<name>.ts.net` and stays the same; ShareFolder also tries to make a short `spoo.me/...` name for it. Keep the Tailscale app running.

**Or with your own domain:**

If you have a domain (a website name such as `yourname.com`, about $10 a year) on a free Cloudflare account, your link can be `files.yourname.com`, the same every time:

1. On the ShareFolder page, under the link, click **Get a short link that never changes**.
2. **Connect Cloudflare**: a Cloudflare page opens in the browser. Log in, click your domain, press **Authorize**, and come back.
3. Type the address you want (for example `files.yourname.com`) and press **Use this address**.

ShareFolder makes a Cloudflare tunnel named `sharefolder` and the DNS record for that address, and uses it every time it starts. If the address already points somewhere (your website or email), ShareFolder says so and changes it only if you press **Replace it**. Because this link never changes, the password becomes 10 letters and digits instead of 6 digits. **Change or stop your own link** (under the link) goes back to the free link. If your address stops working (for example the domain expired), ShareFolder uses a free link meanwhile and says why. The Cloudflare connection is kept in `%AppData%\ShareFolder\cloudflare`, apart from any other Cloudflare setup on the PC.

To also share on your WiFi without the internet (faster for phones in the same house), start it with `-lan`. Windows Firewall then asks once; allow it on private networks.

## Good to know

- **Safety.**
  - Visitors only ever see the folder you chose.
  - Links and junctions inside it (such as the hidden "My Music" in Documents), Windows hidden and system files, this program and its settings are never shown, changed or replaced.
  - A wrong password is limited to 8 tries in 10 minutes per visitor, and 60 for everyone together, so the 6-digit password of a free link (which changes every time) can't be guessed in time. Your own link that never changes gets a 10-character password.
  - The control page only opens on your own PC, never through the link.

- **Delete** never erases anything: deleted items go into the hidden `.ShareFolder-Trash` folder inside the shared folder, where you can bring them back.
- The free internet link comes from Cloudflare's quick tunnels. The program downloads Cloudflare's `cloudflared` helper (about 50 MB) once, into `%AppData%\ShareFolder`.
- If none of the short-link websites accepts the link, the page shows the full `trycloudflare.com` link (it works the same) and says so.
- `%AppData%\ShareFolder\log.txt` records what happened, if something needs checking.
- The Cloudflare helper is tied to ShareFolder: when ShareFolder stops in any way, Windows stops the helper too.
- Uploads go in 8 MB pieces, so big files work through the tunnel, and a dropped connection only repeats one piece.
- Shortcuts: Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V (also pastes files and screenshots from your computer), Delete, F2 (rename), Enter (open), Backspace (up), Alt+N (new folder), F5 (refresh).
- Options: `ShareFolder.exe "D:\Photos" -pass newpassword -port 8080 -read-only -lan -no-internet -no-browser`.
  - `-pass` sets a new password (6 or more characters) and keeps it.
  - `-lan` also shares on this WiFi.
  - `-read-only` lets people only browse and download.
  - `-no-internet` shares on the same WiFi only.

## Build

Needs Go 1.22 or newer: `./build.sh` writes `dist/ShareFolder.exe` (Windows 64-bit) and a Linux copy for testing. The page is `web/index.html`, built into the program.
