#!/bin/sh
set -eu
umask 022

REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BREW_PREFIX=$(brew --prefix)
READONLY_JAVA_HOME=${JAVA_HOME:-"$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"}
INSTALL_DIR="$BREW_PREFIX/opt/signal-cli-readonly"
DIST_DIR="$REPO_DIR/build/install/signal-cli"

test -x "$READONLY_JAVA_HOME/bin/java" || { echo 'JDK 25 is required.' >&2; exit 1; }
test -d "$DIST_DIR/lib" || { echo 'Run ./gradlew test installDist first.' >&2; exit 1; }
if [ -e "$INSTALL_DIR" ] || [ -L "$INSTALL_DIR" ]; then
    echo "Refusing to overwrite an existing installation: $INSTALL_DIR" >&2
    exit 1
fi
if [ -e "$BREW_PREFIX/bin/signal-cli-readonly" ] || [ -L "$BREW_PREFIX/bin/signal-cli-readonly" ]; then
    echo 'signal-cli-readonly already exists; inspect it before reinstalling.' >&2
    exit 1
fi

mkdir -p "$INSTALL_DIR/bin"
cp -R "$DIST_DIR/lib" "$INSTALL_DIR/lib"
cp "$REPO_DIR/READ_ONLY.md" "$REPO_DIR/LICENSE" "$INSTALL_DIR/"
ln -s "$READONLY_JAVA_HOME" "$INSTALL_DIR/java-home"
cat > "$INSTALL_DIR/bin/signal-cli-readonly" <<'LAUNCHER'
#!/bin/sh
set -eu
umask 077
ulimit -c 0
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS SIGNAL_CLI_OPTS CLASSPATH
# Resolve the public symlink without relying on GNU readlink -f.
SCRIPT=$0
while [ -L "$SCRIPT" ]; do
    SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$SCRIPT")" && pwd)
    TARGET=$(readlink "$SCRIPT")
    case "$TARGET" in
        /*) SCRIPT=$TARGET ;;
        *) SCRIPT=$SCRIPT_DIR/$TARGET ;;
    esac
done
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$SCRIPT")/.." && pwd)
exec "$APP_DIR/java-home/bin/java" \
    --enable-native-access=ALL-UNNAMED \
    -XX:-HeapDumpOnOutOfMemoryError -XX:-CreateCoredumpOnCrash \
    -XX:ErrorFile=/dev/null \
    -cp "$APP_DIR/lib/*" org.asamk.signal.Main "$@"
LAUNCHER
chmod 755 "$INSTALL_DIR/bin/signal-cli-readonly"
ln -s "$INSTALL_DIR/bin/signal-cli-readonly" "$BREW_PREFIX/bin/signal-cli-readonly"
"$BREW_PREFIX/bin/signal-cli-readonly" --version
echo "Installed: $BREW_PREFIX/bin/signal-cli-readonly"
