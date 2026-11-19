//go:build !windows

package main

import (
	"errors"
	"io/fs"
	"log"
	"os/exec"
	"strings"
)

func hiddenAttr(fi fs.FileInfo) bool { return false }

func keepAwake() {}

func noConsole(cmd *exec.Cmd) {}

func pickFolder(start string) (string, error) {
	if _, err := exec.LookPath("zenity"); err != nil {
		return "", errors.New("no folder window on this system")
	}
	out, err := exec.Command("zenity", "--file-selection", "--directory", "--title=Choose the folder you want to share").Output()
	if err != nil {
		return "", nil // cancelled
	}
	return strings.TrimSpace(string(out)), nil
}

func openFolder(p string) {
	if p != "" {
		exec.Command("xdg-open", p).Start()
	}
}

func showError(msg string) { log.Println("Problem:", msg) }
