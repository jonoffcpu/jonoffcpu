#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Renders the d2 diagram sources in docs/diagrams/ to SVGs in docs/images/.
#
#   docs/diagrams/render.sh               render sources git sees as modified or untracked,
#                                         plus any whose SVG is missing or older than the source
#   docs/diagrams/render.sh --all         render every source
#   docs/diagrams/render.sh NAME...       render the named diagrams (with or without .d2)
#   docs/diagrams/render.sh --watch NAME  re-render NAME on every save and live-reload it in
#                                         the browser (d2 --watch serves one diagram at a time)

set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
source_dir=$repo_dir/docs/diagrams
image_dir=$repo_dir/docs/images
layout=tala
pad=40 # d2 default is 100 px of empty margin on every side

usage() {
  sed -n '4,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

if ! command -v d2 >/dev/null 2>&1; then
  echo "d2 is not installed; see https://d2lang.com/tour/install (for example: brew install d2)" >&2
  exit 1
fi

# Maps a diagram name or path to its source file, rejecting anything outside docs/diagrams/.
source_of() {
  local name=${1%.d2}
  name=${name##*/}
  local src=$source_dir/$name.d2
  if [[ ! -f "$src" ]]; then
    echo "no such diagram: $1 (expected $src)" >&2
    exit 1
  fi
  printf '%s\n' "$src"
}

render() {
  local src=$1
  local name
  name=$(basename "${src%.d2}")
  local out=$image_dir/$name.svg
  d2 --layout="$layout" --pad "$pad" "$src" "$out"
  echo "rendered $out"
}

mode=changed
watch_name=
names=()
while (($# > 0)); do
  case $1 in
    --all) mode=all ;;
    --watch)
      mode=watch
      shift
      (($# == 1)) || usage
      watch_name=$1
      ;;
    -h | --help) usage ;;
    -*) usage ;;
    *)
      mode=named
      names+=("$1")
      ;;
  esac
  shift
done

case $mode in
  watch)
    src=$(source_of "$watch_name")
    out=$image_dir/$(basename "${src%.d2}").svg
    echo "watching $src -> $out (Ctrl-C to stop)"
    exec d2 --layout="$layout" --pad "$pad" --watch "$src" "$out"
    ;;
  named)
    for name in "${names[@]}"; do
      src=$(source_of "$name")
      render "$src"
    done
    ;;
  all)
    for src in "$source_dir"/*.d2; do
      render "$src"
    done
    ;;
  changed)
    selected=()
    # Modified, added, renamed, or untracked sources.
    while IFS= read -r -d '' path; do
      [[ $path == *.d2 ]] && selected+=("$repo_dir/$path")
    done < <(git -C "$repo_dir" status --porcelain -z --untracked-files=all -- docs/diagrams \
      | while IFS= read -r -d '' entry; do
          status=${entry:0:2}
          path=${entry:3}
          # A rename entry is followed by its original path; skip that extra record.
          if [[ $status == R* ]]; then IFS= read -r -d '' _; fi
          printf '%s\0' "$path"
        done)
    # Clean sources whose rendered SVG is missing or stale.
    for src in "$source_dir"/*.d2; do
      out=$image_dir/$(basename "${src%.d2}").svg
      if [[ ! -f "$out" || "$src" -nt "$out" ]]; then
        echo "warning: $out is missing or older than its source" >&2
        selected+=("$src")
      fi
    done
    if ((${#selected[@]} == 0)); then
      echo "no changed diagrams"
      exit 0
    fi
    for src in $(printf '%s\n' "${selected[@]}" | sort -u); do
      render "$src"
    done
    ;;
esac
