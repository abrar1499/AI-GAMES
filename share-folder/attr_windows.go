//go:build windows

package main

import (
	"io/fs"
	"os/exec"
	"runtime"
	"sync"
	"syscall"
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

// The cloudflared helpers belong to a Windows "job" that ends with ShareFolder: if ShareFolder stops in any
// way (Stop sharing, Task Manager, a crash), Windows ends them too, so no tunnel keeps running on its own.
var (
	jobOnce   sync.Once
	jobHandle uintptr
)

type jobBasicLimits struct {
	PerProcessUserTimeLimit int64
	PerJobUserTimeLimit     int64
	LimitFlags              uint32
	MinimumWorkingSetSize   uintptr
	MaximumWorkingSetSize   uintptr
	ActiveProcessLimit      uint32
	Affinity                uintptr
	PriorityClass           uint32
	SchedulingClass         uint32
}

type jobExtendedLimits struct {
	Basic                 jobBasicLimits
	IoInfo                [6]uint64
	ProcessMemoryLimit    uintptr
	JobMemoryLimit        uintptr
	PeakProcessMemoryUsed uintptr
	PeakJobMemoryUsed     uintptr
}

func bindChild(cmd *exec.Cmd) {
	k := syscall.NewLazyDLL("kernel32.dll")
	jobOnce.Do(func() {
		h, _, _ := k.NewProc("CreateJobObjectW").Call(0, 0)
		if h == 0 {
			return
		}
		info := jobExtendedLimits{Basic: jobBasicLimits{LimitFlags: 0x2000}} // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
		ok, _, _ := k.NewProc("SetInformationJobObject").Call(h, 9,          // JobObjectExtendedLimitInformation
			uintptr(unsafe.Pointer(&info)), unsafe.Sizeof(info))
		if ok == 0 {
			syscall.CloseHandle(syscall.Handle(h))
			return
		}
		jobHandle = h
	})
	if jobHandle == 0 || cmd.Process == nil {
		return
	}
	p, err := syscall.OpenProcess(0x0100|0x0001, false, uint32(cmd.Process.Pid)) // PROCESS_SET_QUOTA | PROCESS_TERMINATE
	if err != nil {
		return
	}
	k.NewProc("AssignProcessToJobObject").Call(jobHandle, uintptr(p))
	syscall.CloseHandle(p)
}

// drives lists this PC's drives ("Local Disk (C:)", "USB drive (E:)") for the folder chooser.
func drives() []place {
	k := syscall.NewLazyDLL("kernel32.dll")
	mask, _, _ := k.NewProc("GetLogicalDrives").Call()
	typ := k.NewProc("GetDriveTypeW")
	var out []place
	for i := 0; i < 26; i++ {
		if mask&(1<<uint(i)) == 0 {
			continue
		}
		root := string(rune('A'+i)) + ":\\"
		p, _ := syscall.UTF16PtrFromString(root)
		t, _, _ := typ.Call(uintptr(unsafe.Pointer(p)))
		label := map[uintptr]string{2: "USB drive", 3: "Local Disk", 4: "Network drive", 5: "CD/DVD drive", 6: "RAM disk"}[t]
		if label == "" {
			label = "Drive"
		}
		out = append(out, place{Name: label + " (" + string(rune('A'+i)) + ":)", Path: root, Kind: "drive"})
	}
	return out
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
