//go:build windows

package main

import (
	"encoding/base64"
	"io/fs"
	"os/exec"
	"runtime"
	"strings"
	"syscall"
	"unicode/utf16"
	"unsafe"
)

// hiddenAttr reports Windows hidden or system items (desktop.ini, Thumbs.db, $RECYCLE.BIN, ...), which are never shown or served.
func hiddenAttr(fi fs.FileInfo) bool {
	if d, ok := fi.Sys().(*syscall.Win32FileAttributeData); ok {
		return d.FileAttributes&(syscall.FILE_ATTRIBUTE_HIDDEN|syscall.FILE_ATTRIBUTE_SYSTEM) != 0
	}
	return false
}

// keepAwake stops Windows from going to sleep while the folder is shared (the screen may still turn off).
func keepAwake() {
	go func() {
		runtime.LockOSThread()
		proc := syscall.NewLazyDLL("kernel32.dll").NewProc("SetThreadExecutionState")
		if proc.Find() == nil {
			proc.Call(0x80000001) // ES_CONTINUOUS | ES_SYSTEM_REQUIRED, for as long as this thread lives
		}
		select {}
	}()
}

// noConsole starts a console program (cloudflared, PowerShell) without a black window.
func noConsole(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}

// pickFolder shows Windows' own "choose a folder" window, on top of the browser, and returns the folder ("" if cancelled).
func pickFolder(start string) (string, error) {
	script := `Add-Type -AssemblyName System.Windows.Forms
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$o = New-Object System.Windows.Forms.Form
$o.TopMost = $true
$o.ShowInTaskbar = $false
$d = New-Object System.Windows.Forms.FolderBrowserDialog
$d.Description = 'Choose the folder you want to share'
$d.ShowNewFolderButton = $true
`
	if start != "" {
		script += "$d.SelectedPath = '" + strings.ReplaceAll(start, "'", "''") + "'\n"
	}
	script += "if ($d.ShowDialog($o) -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $d.SelectedPath }\n"
	u := utf16.Encode([]rune(script))
	b := make([]byte, len(u)*2)
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	cmd := exec.Command("powershell.exe", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-EncodedCommand", base64.StdEncoding.EncodeToString(b))
	noConsole(cmd)
	out, err := cmd.Output()
	return strings.TrimSpace(strings.TrimPrefix(string(out), "\ufeff")), err
}

// openFolder opens the folder in File Explorer on this PC.
func openFolder(p string) {
	if p != "" {
		exec.Command("explorer.exe", p).Start()
	}
}

// showError tells the owner something went wrong, in a small Windows message box.
func showError(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(msg)
	c, _ := syscall.UTF16PtrFromString("ShareFolder")
	user32.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x30) // MB_ICONWARNING
}
