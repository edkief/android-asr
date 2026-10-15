# Source me: `. scripts/env.sh` or `source scripts/env.sh`. Do NOT run me.
# This file must not use `set -e` because we are sourced and may be appended to.

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
