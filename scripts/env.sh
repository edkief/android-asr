# Source me: `. scripts/env.sh` or `source scripts/env.sh`. Do NOT run me.
# This file must not use `set -e` because we are sourced and may be appended to.
#
# Per-machine override: if scripts/env.<hostname>.sh exists (hostname as
# printed by `uname -n`), it is sourced INSTEAD of the rest of this file.
# A host file may itself `source` this env.sh to reuse the defaults and then
# override a few variables; the guard below stops that from looping.

if [[ -z "${_CONVOSCRIBE_ENV_HOST_LOADING:-}" ]]; then
  # Directory of this file when sourced: BASH_SOURCE in bash, %x in zsh.
  _convoscribe_env_dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)"
  _convoscribe_host_env="$_convoscribe_env_dir/env.$(uname -n).sh"
  unset _convoscribe_env_dir
  if [[ -f "$_convoscribe_host_env" ]]; then
    export _CONVOSCRIBE_ENV_HOST_LOADING=1
    source "$_convoscribe_host_env"
    unset _CONVOSCRIBE_ENV_HOST_LOADING _convoscribe_host_env
    return 0
  fi
  unset _convoscribe_host_env
fi

export JAVA_HOME="$HOME/.local/share/convoscribe-toolchain/jdk-17"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK="$ANDROID_HOME/ndk/27.2.12479018"

prepend_path() {
  case ":$PATH:" in
    *":$1:"*) ;;
    *) export PATH="$1:$PATH" ;;
  esac
}

prepend_path "$JAVA_HOME/bin"
prepend_path "$HOME/.local/share/convoscribe-toolchain/gradle-8.11.1/bin"
prepend_path "$ANDROID_HOME/cmdline-tools/latest/bin"
prepend_path "$ANDROID_HOME/platform-tools"

# CMake directory layout: setup-toolchain.sh installs the version pinned by
# CMAKE_VERSION inside scripts/setup-toolchain.sh. We add any matching directory
# we can find so env.sh does not have to know the current version.
for cmake_dir in "$HOME/.local/share/convoscribe-toolchain"/cmake-*/bin; do
  if [[ -d "$cmake_dir" ]]; then
    prepend_path "$cmake_dir"
    break
  fi
done
