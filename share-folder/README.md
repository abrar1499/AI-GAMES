# ShareFolder

Share one folder of your Windows PC over the internet, behind a password, in a page that works like File Explorer: browse, search, upload (files and whole folders), download (a file, or several files and folders as one ZIP), new folder, rename, delete, cut, copy and paste, drag and drop, Ctrl+C / Ctrl+X / Ctrl+V, right-click menu, previews of pictures, videos, sound, PDFs and text.

The files stay on your PC. People who have the link and the password open them straight from it, so there is no storage limit and nothing is uploaded anywhere else.

## Use it

1. Download `dist/ShareFolder.exe`.
2. Double-click it. Windows may say "Windows protected your PC" because the program is not signed: click **More info**, then **Run anyway**.
3. It asks which folder to share: paste the folder's path (in Explorer, click the address bar and copy it) and press Enter. Or drag the folder onto `ShareFolder.exe`.
4. The black window shows:
   - **Password**: made once and kept for next time.
   - **On this PC** link (your browser opens it by itself, already signed in).
   - After a few seconds, the **INTERNET LINK** (`https://….trycloudflare.com`).
5. Send the internet link and the password to the people you want. They open it in any browser, on a phone too.
6. Keep the window open while they use it. The program keeps the PC from sleeping while it runs, but closing a laptop lid or the window stops the sharing.

To also share on your WiFi without the internet (faster for phones in the same house), start it with `-lan`. Windows Firewall then asks once; allow it on private networks.

## Good to know

- **Safety.**
  - Visitors only ever see the folder you chose.
  - Links and junctions inside it (such as the hidden "My Music" in Documents), Windows hidden and system files, this program and its settings are never shown, changed or replaced.
  - A wrong password is limited to 8 tries in 10 minutes per visitor.

- **Delete** never erases anything: deleted items go into the hidden `.ShareFolder-Trash` folder inside the shared folder, where you can bring them back.
- The internet link comes from Cloudflare's free quick tunnels. The program downloads Cloudflare's `cloudflared` helper (about 50 MB) once, into `%AppData%\ShareFolder`. The address changes every time you start the program.
- Uploads go in 8 MB pieces, so big files work through the tunnel, and a dropped connection only repeats one piece.
- Shortcuts: Ctrl+A, Ctrl+C, Ctrl+X, Ctrl+V (also pastes files and screenshots from your computer), Delete, F2 (rename), Enter (open), Backspace (up), Alt+N (new folder), F5 (refresh).
- Options: `ShareFolder.exe "D:\Photos" -pass newpassword -port 8080 -read-only -lan -no-internet -no-browser`.
  - `-pass` sets a new password of 8 or more characters and keeps it.
  - `-lan` also shares on this WiFi.
  - `-read-only` lets people only browse and download.
  - `-no-internet` shares on the same WiFi only.

## Build

Needs Go 1.22 or newer: `./build.sh` writes `dist/ShareFolder.exe` (Windows 64-bit) and a Linux copy for testing. The page is `web/index.html`, built into the program.
