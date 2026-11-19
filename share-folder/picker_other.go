//go:build !windows

package main

import "errors"

var (
	errPickerOpen = errors.New("The folder window is already open.")
	pickerNative  = false
)

// pickFolder: only Windows has the folder window; elsewhere the page shows its own list.
func pickFolder(start string) (string, error) {
	return "", errors.New("no folder window on this system")
}

func raisePicker() bool { return false }

func closePicker() bool { return false }
