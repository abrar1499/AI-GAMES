//go:build windows

package main

import (
	"io/fs"
	"runtime"
	"syscall"
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
