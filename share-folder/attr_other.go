//go:build !windows

package main

import (
	"io/fs"
	"log"
	"os/exec"
)

func hiddenAttr(fi fs.FileInfo) bool { return false }

func keepAwake() {}

func noConsole(cmd *exec.Cmd) {}

func drives() []place { return []place{{Name: "Computer (/)", Path: "/", Kind: "drive"}} }

func openFolder(p string) {
	if p != "" {
		exec.Command("xdg-open", p).Start()
	}
}

func showError(msg string) { log.Println("Problem:", msg) }
