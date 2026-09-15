#!/data/data/com.termux/files/usr/bin/bash
# Runs on the phone, in Termux -- which has no /usr/bin/env, hence the
# absolute interpreter. build.sh keeps env(1) since it runs in the container.
# Push a fragment shader into the running app. No rebuild, no reinstall.
#
#   ./push.sh assets/shaders/Tunnel.frag    compile and show it
#   ./push.sh -w assets/shaders/Tunnel.frag watch the file, push on every save
#   ./push.sh -l                            list presets, current one starred
#   ./push.sh -s Voronoi                    switch preset
#   ./push.sh -r Tunnel                     drop the pushed version, restore built-in
#
#   ./push.sh -p                            build plugin/ and hot-swap the dex in
#   ./push.sh -p some.dex                   push a dex you already have
#   ./push.sh -c 'cycle 6'                  send a command to the plugin
#   ./push.sh -i                            plugin status and recent log
#   ./push.sh -P                            detach the plugin
#
#   ./push.sh -f data.json                  push a file into the plugin's data dir
#   ./push.sh -F                            list those files
#
# The preset name is the filename without .frag. A name the app has never seen
# is appended to the cycle, so new presets need no reinstall either.
set -uo pipefail
cd "$(dirname "$0")"

PORT_BASE=8777
PORT_TRIES=10

die() { echo "push: $*" >&2; exit 1; }

# Never proxy a loopback request. Claude Code (and anything else that exports
# http_proxy) would otherwise swallow every call to the app and answer for it.
CURL=(curl --noproxy '*' -g -s)

# Which loopback the app got depends on the device: InetAddress.getLoopbackAddress()
# hands back ::1 where IPv6 is up, 127.0.0.1 otherwise. Try both, and take the
# first free port in the range since that is how the app picks one.
find_base() {
  local host p url
  for host in 127.0.0.1 "[::1]"; do
    for (( p = PORT_BASE; p < PORT_BASE + PORT_TRIES; p++ )); do
      url="http://$host:$p"
      if "${CURL[@]}" -f -m 1 "$url/health" >/dev/null 2>&1; then
        echo "$url"
        return 0
      fi
    done
  done
  return 1
}

BASE=$(find_base) || die "app isn't listening.
Open the shader app and leave it on screen -- the push channel only runs in the foreground."

HEAD_LINES=$("${CURL[@]}" -f -m 2 "$BASE/health" | command awk '/^headLines /{print $2}')
: "${HEAD_LINES:=0}"

# GLSL logs number lines in the concatenated source (shared preamble + your
# file), so shift them back to where they are in the file you edited.
remap() {
  command awk -v off="$HEAD_LINES" '{
    while (match($0, /[0-9]+:[0-9]+/)) {
      tok = substr($0, RSTART, RLENGTH)
      split(tok, a, ":")
      printf "%s%s:%d", substr($0, 1, RSTART - 1), a[1], a[2] - off
      $0 = substr($0, RSTART + RLENGTH)
    }
    print $0
  }'
}

request() {  # method path [file]
  local method=$1 path=$2 file=${3:-} out code
  out=$(mktemp)
  if [ -n "$file" ]; then
    code=$("${CURL[@]}" -o "$out" -w '%{http_code}' -m 10 \
      -X "$method" --data-binary "@$file" \
      -H 'Content-Type: text/plain' "$BASE$path")
  else
    code=$("${CURL[@]}" -o "$out" -w '%{http_code}' -m 10 -X "$method" "$BASE$path")
  fi
  if [ "$code" = "200" ]; then
    command cat "$out"
    rm -f "$out"
    return 0
  fi
  echo "--- $code ---" >&2
  remap < "$out" >&2
  rm -f "$out"
  return 1
}

push_file() {
  local file=$1 name
  [ -f "$file" ] || die "no such file: $file"
  name=$(command basename "$file"); name=${name%.frag}
  request POST "/preset/$name" "$file"
}

case "${1:-}" in
  -l|--list)   request GET  /presets ;;
  -i|--info)   request GET  /plugin ;;
  -F|--files)  request GET  /files ;;
  -f|--file)
    [ $# -ge 2 ] || die "usage: push.sh -f <file> [name]"
    [ -f "$2" ] || die "no such file: $2"
    name=${3:-$(command basename "$2")}
    request POST "/file/$name" "$2" ;;
  -P|--drop-plugin) request DELETE /plugin ;;
  -c|--command)
    [ $# -ge 2 ] || die "usage: push.sh -c '<text>'"
    tmp=$(mktemp); printf '%s' "$2" > "$tmp"
    request POST /command "$tmp"; rc=$?
    rm -f "$tmp"; exit $rc ;;
  -p|--plugin)
    dex=${2:-}
    if [ -z "$dex" ]; then
      # The plugin links against the app's own classes, so it builds in the
      # same container the APK does.
      echo "building plugin..."
      proot-distro login "${DISTRO:-ubuntu}" --bind "$PWD:/mnt/shaderapp" -- \
        /bin/bash -c "cd /mnt/shaderapp && ./build-plugin.sh" || die "plugin build failed"
      dex=plugin/build/classes.dex
    fi
    [ -f "$dex" ] || die "no such dex: $dex"
    echo "pushing $(command wc -c < "$dex") bytes of dex"
    request POST /plugin "$dex" ;;
  -s|--select) [ $# -ge 2 ] || die "usage: push.sh -s <Name>"
               request POST "/select/$2" ;;
  -r|--revert) [ $# -ge 2 ] || die "usage: push.sh -r <Name>"
               request DELETE "/preset/$2" ;;
  -w|--watch)
    [ $# -ge 2 ] || die "usage: push.sh -w <file.frag>"
    file=$2
    [ -f "$file" ] || die "no such file: $file"
    echo "watching $file -- ctrl-c to stop"
    last=""
    while true; do
      now=$(command stat -c %Y "$file" 2>/dev/null || echo "")
      if [ -n "$now" ] && [ "$now" != "$last" ]; then
        last=$now
        printf '%s  ' "$(date +%H:%M:%S)"
        push_file "$file" || true
      fi
      command sleep 0.5
    done
    ;;
  ""|-h|--help)
    command sed -n '4,25p' "$0" | command sed 's/^# \{0,1\}//'
    echo
    echo "app is at $BASE"
    ;;
  *) push_file "$1" ;;
esac
