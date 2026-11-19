//go:build windows

package main

// The Windows "Select folder" window (the same one File Explorer uses), opened from the control page.
// It is shown on its own thread and pushed in front of the browser, because Windows normally keeps a
// window from a background program behind the one the owner is using.

import (
	"errors"
	"fmt"
	"runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var (
	modOle32    = syscall.NewLazyDLL("ole32.dll")
	modShell32  = syscall.NewLazyDLL("shell32.dll")
	modUser32   = syscall.NewLazyDLL("user32.dll")
	modKernel32 = syscall.NewLazyDLL("kernel32.dll")

	procCoInitializeEx              = modOle32.NewProc("CoInitializeEx")
	procCoUninitialize              = modOle32.NewProc("CoUninitialize")
	procCoCreateInstance            = modOle32.NewProc("CoCreateInstance")
	procCoTaskMemFree               = modOle32.NewProc("CoTaskMemFree")
	procSHCreateItemFromParsingName = modShell32.NewProc("SHCreateItemFromParsingName")
	procEnumThreadWindows           = modUser32.NewProc("EnumThreadWindows")
	procGetClassNameW               = modUser32.NewProc("GetClassNameW")
	procIsWindowVisible             = modUser32.NewProc("IsWindowVisible")
	procIsIconic                    = modUser32.NewProc("IsIconic")
	procShowWindow                  = modUser32.NewProc("ShowWindow")
	procSetWindowPos                = modUser32.NewProc("SetWindowPos")
	procGetForegroundWindow         = modUser32.NewProc("GetForegroundWindow")
	procSetForegroundWindow         = modUser32.NewProc("SetForegroundWindow")
	procBringWindowToTop            = modUser32.NewProc("BringWindowToTop")
	procGetWindowThreadProcessId    = modUser32.NewProc("GetWindowThreadProcessId")
	procAttachThreadInput           = modUser32.NewProc("AttachThreadInput")
	procKeybdEvent                  = modUser32.NewProc("keybd_event")
	procPostMessageW                = modUser32.NewProc("PostMessageW")
	procGetCurrentThreadId          = modKernel32.NewProc("GetCurrentThreadId")
	procCreateWindowExW             = modUser32.NewProc("CreateWindowExW")
	procDestroyWindow               = modUser32.NewProc("DestroyWindow")
	procGetWindow                   = modUser32.NewProc("GetWindow")
	procGetWindowLongPtrW           = modUser32.NewProc("GetWindowLongPtrW")
	procGetSystemMetrics            = modUser32.NewProc("GetSystemMetrics")
)

const (
	hwndTopmost   = ^uintptr(0)  // (HWND)-1
	gwlExStyle    = ^uintptr(19) // -20
	wsExTopmost   = 0x8
	swpNoSize     = 0x1
	swpNoMove     = 0x2
	swpNoActivate = 0x10
	swpShowWindow = 0x40
)

type guid struct {
	d1     uint32
	d2, d3 uint16
	d4     [8]byte
}

var (
	clsidFileOpenDialog = guid{0xDC1C5A9C, 0xE88A, 0x4DDE, [8]byte{0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7}}
	iidFileOpenDialog   = guid{0xD57C7288, 0xD4AD, 0x4768, [8]byte{0xBE, 0x02, 0x9D, 0x96, 0x95, 0x32, 0xD9, 0x60}}
	iidShellItem        = guid{0x43826D1E, 0xE718, 0x42EE, [8]byte{0xBC, 0x55, 0xA1, 0xE2, 0x61, 0xC3, 0x7B, 0xFE}}
)

// Places in the COM method tables (IUnknown, IModalWindow, IFileDialog, IShellItem).
const (
	mRelease          = 2
	mShow             = 3  // IModalWindow::Show
	mSetOptions       = 9  // IFileDialog::SetOptions
	mGetOptions       = 10 // IFileDialog::GetOptions
	mSetFolder        = 12 // IFileDialog::SetFolder
	mSetFileName      = 15 // IFileDialog::SetFileName
	mSetTitle         = 17 // IFileDialog::SetTitle
	mSetOkButtonLabel = 18 // IFileDialog::SetOkButtonLabel
	mGetResult        = 20 // IFileDialog::GetResult
	mGetDisplayName   = 5  // IShellItem::GetDisplayName

	fosNoChangeDir      = 0x8
	fosPickFolders      = 0x20
	fosForceFileSystem  = 0x40
	fosPathMustExist    = 0x800
	sigdnFileSysPath    = 0x80058000
	errCancelled        = 0x800704C7 // HRESULT_FROM_WIN32(ERROR_CANCELLED)
	coinitApartment     = 0x2
	coinitDisableOle1DD = 0x4
)

// method finds a COM object's method number i.
func method(obj unsafe.Pointer, i uintptr) uintptr {
	vtbl := *(*unsafe.Pointer)(obj)
	return *(*uintptr)(unsafe.Add(vtbl, i*unsafe.Sizeof(uintptr(0))))
}

func failed(hr uintptr) bool { return int32(uint32(hr)) < 0 }

func release(obj unsafe.Pointer) {
	if obj != nil {
		syscall.SyscallN(method(obj, mRelease), uintptr(obj))
	}
}

var picker struct {
	mu     sync.Mutex
	open   bool
	thread uintptr // the thread that shows the window
	owner  uintptr // the hidden always-on-top window that owns it
}

var (
	errPickerOpen = errors.New("The folder window is already open.")
	pickerNative  = true
)

// pickFolder shows the Select folder window, starting in start's folder with start selected.
// It gives back the chosen folder, or "" when the owner pressed Cancel.
func pickFolder(start string) (string, error) {
	picker.mu.Lock()
	if picker.open {
		picker.mu.Unlock()
		raisePicker()
		return "", errPickerOpen
	}
	picker.open = true
	picker.mu.Unlock()
	defer func() {
		picker.mu.Lock()
		picker.open, picker.thread, picker.owner = false, 0, 0
		picker.mu.Unlock()
	}()
	type result struct {
		path string
		err  error
	}
	done := make(chan result, 1)
	go func() {
		runtime.LockOSThread() // never unlocked: the thread ends with this goroutine
		p, err := showFolderWindow(start)
		done <- result{p, err}
	}()
	r := <-done
	return r.path, r.err
}

func showFolderWindow(start string) (string, error) {
	if hr, _, _ := procCoInitializeEx.Call(0, coinitApartment|coinitDisableOle1DD); !failed(hr) {
		defer procCoUninitialize.Call()
	}
	var dlg unsafe.Pointer
	hr, _, _ := procCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidFileOpenDialog)), 0, 1, // CLSCTX_INPROC_SERVER
		uintptr(unsafe.Pointer(&iidFileOpenDialog)), uintptr(unsafe.Pointer(&dlg)))
	if failed(hr) || dlg == nil {
		return "", fmt.Errorf("the folder window could not open (%#x)", uint32(hr))
	}
	defer release(dlg)

	var opts uint32
	syscall.SyscallN(method(dlg, mGetOptions), uintptr(dlg), uintptr(unsafe.Pointer(&opts)))
	syscall.SyscallN(method(dlg, mSetOptions), uintptr(dlg), uintptr(opts|fosPickFolders|fosForceFileSystem|fosPathMustExist|fosNoChangeDir))
	title, _ := syscall.UTF16PtrFromString("Choose the folder to share")
	syscall.SyscallN(method(dlg, mSetTitle), uintptr(dlg), uintptr(unsafe.Pointer(title)))
	okLabel, _ := syscall.UTF16PtrFromString("Share this folder")
	syscall.SyscallN(method(dlg, mSetOkButtonLabel), uintptr(dlg), uintptr(unsafe.Pointer(okLabel)))
	if start != "" {
		dir, name := splitForPicker(start)
		if p, err := syscall.UTF16PtrFromString(dir); err == nil {
			var item unsafe.Pointer
			hr, _, _ := procSHCreateItemFromParsingName.Call(uintptr(unsafe.Pointer(p)), 0, uintptr(unsafe.Pointer(&iidShellItem)), uintptr(unsafe.Pointer(&item)))
			if !failed(hr) && item != nil {
				syscall.SyscallN(method(dlg, mSetFolder), uintptr(dlg), uintptr(item))
				release(item)
				if n, err := syscall.UTF16PtrFromString(name); err == nil && name != "" {
					syscall.SyscallN(method(dlg, mSetFileName), uintptr(dlg), uintptr(unsafe.Pointer(n)))
				}
			}
		}
	}

	// The window belongs to a hidden, always-on-top owner in the middle of the screen: it opens
	// centred, above the browser and every other window, and stays there until it is closed.
	cx, _, _ := procGetSystemMetrics.Call(0) // SM_CXSCREEN
	cy, _, _ := procGetSystemMetrics.Call(1) // SM_CYSCREEN
	cls, _ := syscall.UTF16PtrFromString("STATIC")
	caption, _ := syscall.UTF16PtrFromString("ShareFolder")
	owner, _, _ := procCreateWindowExW.Call(wsExTopmost|0x80, // WS_EX_TOOLWINDOW: no taskbar button of its own
		uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(caption)), 0x80000000, // WS_POPUP, hidden
		cx/2, cy/2, 1, 1, 0, 0, 0, 0)
	if owner != 0 {
		defer procDestroyWindow.Call(owner)
	}
	tid, _, _ := procGetCurrentThreadId.Call()
	picker.mu.Lock()
	picker.thread, picker.owner = tid, owner
	picker.mu.Unlock()
	go keepPickerOnTop()
	hr, _, _ = syscall.SyscallN(method(dlg, mShow), uintptr(dlg), owner)
	picker.mu.Lock()
	picker.thread, picker.owner = 0, 0 // the window is gone: keepPickerOnTop stops
	picker.mu.Unlock()
	if uint32(hr) == errCancelled {
		return "", nil
	}
	if failed(hr) {
		return "", fmt.Errorf("the folder window closed with an error (%#x)", uint32(hr))
	}
	var item unsafe.Pointer
	hr, _, _ = syscall.SyscallN(method(dlg, mGetResult), uintptr(dlg), uintptr(unsafe.Pointer(&item)))
	if failed(hr) || item == nil {
		return "", fmt.Errorf("the chosen folder could not be read (%#x)", uint32(hr))
	}
	defer release(item)
	var name *uint16
	hr, _, _ = syscall.SyscallN(method(item, mGetDisplayName), uintptr(item), sigdnFileSysPath, uintptr(unsafe.Pointer(&name)))
	if failed(hr) || name == nil {
		return "", errors.New("this is not a folder on the PC (choose a normal folder or drive)")
	}
	defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(name)))
	return utf16PtrString(name), nil
}

func utf16PtrString(p *uint16) string {
	n := 0
	for *(*uint16)(unsafe.Add(unsafe.Pointer(p), n*2)) != 0 {
		n++
	}
	return syscall.UTF16ToString(unsafe.Slice(p, n))
}

// pickerWindow finds the folder window, if it is open.
var (
	enumMu    sync.Mutex
	enumFound uintptr
	enumProc  = syscall.NewCallback(func(hwnd, _ uintptr) uintptr {
		var buf [32]uint16
		n, _, _ := procGetClassNameW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
		if vis, _, _ := procIsWindowVisible.Call(hwnd); vis != 0 && syscall.UTF16ToString(buf[:n]) == "#32770" {
			enumFound = hwnd
			return 0 // stop
		}
		return 1
	})
)

func pickerWindow() uintptr {
	picker.mu.Lock()
	tid, owner := picker.thread, picker.owner
	picker.mu.Unlock()
	if tid == 0 {
		return 0
	}
	if owner != 0 {
		if w, _, _ := procGetWindow.Call(owner, 6); w != 0 && w != owner { // GW_ENABLEDPOPUP: the open window it owns
			if vis, _, _ := procIsWindowVisible.Call(w); vis != 0 {
				return w
			}
		}
	}
	enumMu.Lock()
	defer enumMu.Unlock()
	enumFound = 0
	procEnumThreadWindows.Call(tid, enumProc, 0)
	return enumFound
}

// raisePicker puts the folder window on top of every other window and gives it the keyboard.
func raisePicker() bool {
	hwnd := pickerWindow()
	if hwnd == 0 {
		return false
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if min, _, _ := procIsIconic.Call(hwnd); min != 0 {
		procShowWindow.Call(hwnd, 9) // SW_RESTORE
	}
	procSetWindowPos.Call(hwnd, hwndTopmost, 0, 0, 0, 0, swpNoSize|swpNoMove|swpShowWindow)
	me, _, _ := procGetCurrentThreadId.Call()
	if fg, _, _ := procGetForegroundWindow.Call(); fg != 0 && fg != hwnd {
		if other, _, _ := procGetWindowThreadProcessId.Call(fg, 0); other != 0 && other != me {
			procAttachThreadInput.Call(me, other, 1)
			defer procAttachThreadInput.Call(me, other, 0)
		}
	}
	procSetForegroundWindow.Call(hwnd)
	if fg, _, _ := procGetForegroundWindow.Call(); fg != hwnd {
		// Windows still refuses: a tap of the Alt key counts as the owner's own input, which lets the window come forward
		procKeybdEvent.Call(0x12, 0, 0x1, 0) // VK_MENU down
		procSetForegroundWindow.Call(hwnd)
		procKeybdEvent.Call(0x12, 0, 0x1|0x2, 0) // VK_MENU up
	}
	procBringWindowToTop.Call(hwnd)
	return true
}

// keepPickerOnTop gives the folder window the keyboard as soon as it appears (opening it can take a few
// seconds the first time), and then keeps it above every other window for as long as it is open.
func keepPickerOnTop() {
	raised := false
	for {
		time.Sleep(200 * time.Millisecond)
		picker.mu.Lock()
		open := picker.thread != 0
		picker.mu.Unlock()
		if !open {
			return
		}
		hwnd := pickerWindow()
		if hwnd == 0 {
			continue
		}
		if !raised {
			raised = raisePicker()
			continue
		}
		if procGetWindowLongPtrW.Find() != nil { // 32-bit Windows has no such function: just say it again
			procSetWindowPos.Call(hwnd, hwndTopmost, 0, 0, 0, 0, swpNoSize|swpNoMove|swpNoActivate)
		} else if ex, _, _ := procGetWindowLongPtrW.Call(hwnd, gwlExStyle); ex&wsExTopmost == 0 {
			procSetWindowPos.Call(hwnd, hwndTopmost, 0, 0, 0, 0, swpNoSize|swpNoMove|swpNoActivate)
		}
	}
}

// closePicker closes the folder window as if Cancel was pressed.
func closePicker() bool {
	hwnd := pickerWindow()
	if hwnd != 0 {
		procPostMessageW.Call(hwnd, 0x10, 0, 0) // WM_CLOSE
	}
	return hwnd != 0
}
