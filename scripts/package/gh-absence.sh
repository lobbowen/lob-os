#!/usr/bin/env bash
gh_absent() {
  case "${1,,}" in
    *"not found"*|*"does not exist"*|*"http 404"*|*"no assets"*|*"matching pattern"*|*"no ref found"*) return 0 ;;
    *) return 1 ;;
  esac
}
