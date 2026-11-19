//go:build !windows

package main

import "io/fs"

func hiddenAttr(fi fs.FileInfo) bool { return false }

func keepAwake() {}
