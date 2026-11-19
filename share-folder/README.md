# ShareFolder

Share one folder of your Windows PC over the internet, behind a password, in a page that works like File Explorer: browse, search, upload (files and whole folders), download (a file, or several files and folders as one ZIP), new folder, rename, delete, cut, copy and paste, drag and drop, Ctrl+C / Ctrl+X / Ctrl+V, right-click menu, previews of pictures, videos, sound, PDFs and text.

The files stay on your PC. People who have the link and the password open them straight from it, so there is no storage limit and nothing is uploaded anywhere else.

## Use it

1. Download `dist/ShareFolder.exe`.
2. Double-click it. Windows may say "Windows protected your PC" because the program is not signed: click **More info**, then **Run anyway**.
3. A page opens in your browser:
   - Click **Choose folder** and pick the folder (or paste its path).
   - Wait a few seconds for **your link**, which is shortened to something like `is.gd/Ab12Cd`, with a QR code and a 6-digit **password**.
   - Send them with **Copy link + password** or the **WhatsApp** button. On a phone, people can also just scan the QR code.
4. The people you send it to open the link in any browser (phone too) and type the password.
5. Keep the PC on while they use it. Closing the browser tab does not stop sharing: use **Stop sharing** on that page. Double-click ShareFolder again to bring the page back.

The page also lets you:
- turn **People can add and change files** off (then they can only look and download);
- make a **new password**, or set your own;
- **open the folder page** yourself.

The folder and the settings are remembered for next time. The link is new every time the program starts.

To also share on your WiFi without the internet (faster for phones in the same house), start it with `-lan`. Windows Firewall then asks once; allow it on private networks.

## Good to know

- **Safety.**
  - Visitors only ever see the folder you chose.
  - Links and junctions inside it (such as the hidden "My Music" in Documents), Windows hidden and system files, this program and its settings are never shown, changed or replaced.
  - A wrong password is limited to 8 tries in 10 minutes per visitor, and 60 for everyone together, so even the 6-digit password can't be guessed.
  - The control page only opens on your own PC, never through the link.

- **Delete** never erases anything: deleted items go into the hidden `.ShareFolder-Trash` folder inside the shared folder, where you can bring them back.
- The internet link comes from Cloudflare's free quick tunnels. The program downloads Cloudflare's `cloudflared` helper (about 50 MB) once, into `%AppData%\ShareFolder`, and asks is.gd (or TinyURL) for the short link.
- If the short-link service doesn't answer, the page shows the full `trycloudflare.com` link instead.
- `%AppData%\ShareFolder\log.txt` records what happened, if something needs checking.
- Uploads go in 8 MB pieces, so big files work through the tunnel, and a dropped connection only repeats one piece.
- Shortcuts: Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V (also pastes files and screenshots from your computer), Delete, F2 (rename), Enter (open), Backspace (up), Alt+N (new folder), F5 (refresh).
- Options: `ShareFolder.exe "D:\Photos" -pass newpassword -port 8080 -read-only -lan -no-internet -no-browser`.
  - `-pass` sets a new password (6 or more characters) and keeps it.
  - `-lan` also shares on this WiFi.
  - `-read-only` lets people only browse and download.
  - `-no-internet` shares on the same WiFi only.

## Build

Needs Go 1.22 or newer: `./build.sh` writes `dist/ShareFolder.exe` (Windows 64-bit) and a Linux copy for testing. The page is `web/index.html`, built into the program.
