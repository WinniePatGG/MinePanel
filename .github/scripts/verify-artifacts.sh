#!/usr/bin/env bash
# Verifies that a MinePanel build produced artifacts the plugin can install:
#   1. The main plugin jar is the clean shadow jar and does not leak extension classes.
#   2. All 10 extension jars exist with the "MinePanel-Extension-<Name>-<version>.jar"
#      naming that the web panel's marketplace parses.
#   3. Every extension jar carries a working ServiceLoader entry
#      (META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension) whose
#      class exists inside the jar.
#   4. No extension jar contains classes from a different extension.
# Exits non-zero so the workflow stops before a broken build can be released.
set -euo pipefail

cd "$(dirname "$0")/../.."

# Usage: verify-artifacts.sh [expected-version]
# When a version (e.g. "beta-1.123") is passed, exactly that build is checked.
# Without an argument the jars are auto-detected (local development).
EXPECTED_VERSION="${1:-}"
EXT_ROOT="de/winniepat/minePanel/extensions"
SERVICES_ENTRY="META-INF/services/de.winniepat.minePanel.extensions.MinePanelExtension"
FAIL=0

fail() {
  echo "::error::$1"
  FAIL=1
}

# Read a file out of a jar without relying on unzip being installed.
read_jar_entry() { # $1 = jar, $2 = entry inside the jar
  local jar_abs tmp
  jar_abs="$(cd "$(dirname "$1")" && printf '%s/%s' "$PWD" "$(basename "$1")")"
  tmp="$(mktemp -d)"
  (
    cd "$tmp" || exit 1
    jar xf "$jar_abs" "$2" || exit 1
  ) || {
    rm -rf "$tmp"
    return 1
  }
  cat "$tmp/$2"
  rm -rf "$tmp"
}

has_jar_entry() { # $1 = jar, $2 = exact entry name
  jar tf "$1" | grep -Fq -- "$2"
}

# The 10 extension builds declared in build.gradle: "JarName <package> <Class>"
# (JarName must match the archiveBaseName of the gradle extension jar tasks).
EXTENSIONS=(
  "Reports reports ReportSystemExtension"
  "PlayerManagement playermanagement PlayerManagementExtension"
  "LuckPerms luckperms LuckPermsExtension"
  "PlayerStats playerstats PlayerStatsExtension"
  "Tickets tickets TicketSystemExtension"
  "WorldBackups worldbackups WorldBackupsExtension"
  "Airstrike airstrike AirstrikeExtension"
  "Maintenance maintenance MaintenanceExtension"
  "Whitelist whitelist WhitelistExtension"
  "Announcements announcements AnnouncementsExtension"
)

echo "==> Checking main plugin jar"
if [ -n "$EXPECTED_VERSION" ]; then
  MAIN_JAR="build/libs/MinePanel-${EXPECTED_VERSION}.jar"
  if [ ! -f "$MAIN_JAR" ]; then
    MAIN_JAR=""
    fail "Main plugin jar build/libs/MinePanel-${EXPECTED_VERSION}.jar not found"
  fi
else
  MAIN_JAR="$(find build/libs -maxdepth 1 -name 'MinePanel-*.jar' ! -name '*-plain.jar' -print -quit)"
fi
if [ -z "$MAIN_JAR" ]; then
  fail "Main plugin jar (MinePanel-<version>.jar) not found in build/libs"
else
  echo "Main jar: $MAIN_JAR"
  LISTING="$(jar tf "$MAIN_JAR")"

  # Framework classes that extension jars are loaded against.
  for required in \
    "de/winniepat/minePanel/MinePanel.class" \
    "de/winniepat/minePanel/extensions/MinePanelExtension.class" \
    "de/winniepat/minePanel/extensions/ExtensionManager.class"; do
    if ! grep -Fq -- "$required" <<<"$LISTING"; then
      fail "Main jar is missing required framework class $required"
    fi
  done

  # The main jar must be the shadow (fat) jar: bundled runtime libraries prove the
  # shadowJar output won the race against the plain jar task output.
  if ! grep -Eq -- "^org/sqlite/" <<<"$LISTING"; then
    fail "Main jar does not contain bundled libraries - it is not the shadowJar output"
  fi

  # Extension implementation classes must stay in the extension jars.
  for ext in "${EXTENSIONS[@]}"; do
    pkg="$(awk '{print $2}' <<<"$ext")"
    if grep -Eq -- "^${EXT_ROOT}/${pkg}/" <<<"$LISTING"; then
      fail "Main jar leaks classes from extension package $pkg (shadowJar excludes not applied)"
    fi
  done

  # plugin.yml must be present and expanded (not the '${version}' placeholder).
  PLUGIN_YML="$(read_jar_entry "$MAIN_JAR" plugin.yml || true)"
  if [ -z "$PLUGIN_YML" ]; then
    fail "Main jar does not contain plugin.yml"
  else
    if ! grep -Eq "^version: '.+'" <<<"$PLUGIN_YML"; then
      fail "plugin.yml version was not expanded by processResources"
    fi
    if ! grep -q "main: de.winniepat.minePanel.MinePanel" <<<"$PLUGIN_YML"; then
      fail "plugin.yml main class does not point at the plugin"
    fi
  fi
fi

echo "==> Checking extension jars"
if [ -z "$EXPECTED_VERSION" ]; then
  EXPECTED_VERSION="$(basename "$MAIN_JAR" .jar 2>/dev/null | sed 's/^MinePanel-//' || true)"
fi
echo "Version: $EXPECTED_VERSION"

EXT_JARS="$(find build/libs/extensions -maxdepth 1 -name 'MinePanel-Extension-*.jar' | sort | awk -v suffix="-${EXPECTED_VERSION}.jar" 'substr($0, length($0) - length(suffix) + 1) == suffix')"
if [ -z "$EXT_JARS" ]; then
  fail "No extension jars for version ${EXPECTED_VERSION} were found in build/libs/extensions"
fi

COUNT="$(printf '%s\n' "$EXT_JARS" | grep -c . 2>/dev/null || true)"
COUNT="${COUNT:-0}"
if [ "$COUNT" -ne "${#EXTENSIONS[@]}" ]; then
  fail "Expected ${#EXTENSIONS[@]} extension jars, found $COUNT"
  printf 'Found: %s\n' "$EXT_JARS"
fi

for ext in "${EXTENSIONS[@]}"; do
  read -r name pkg clazz <<<"$ext"
  class_path="${EXT_ROOT}/${pkg}/${clazz}.class"
  service_class="de.winniepat.minePanel.extensions.${pkg}.${clazz}"

  jar_path="$(find build/libs/extensions -maxdepth 1 -name "MinePanel-Extension-${name}-${EXPECTED_VERSION}.jar" -print -quit)"
  if [ -z "$jar_path" ]; then
    fail "Missing extension jar for $name (expected build/libs/extensions/MinePanel-Extension-${name}-${EXPECTED_VERSION}.jar)"
    continue
  fi
  echo "Extension: ${jar_path##*/}"

  if ! has_jar_entry "$jar_path" "$class_path"; then
    fail "$jar_path does not contain $class_path"
  fi

  if ! has_jar_entry "$jar_path" "$SERVICES_ENTRY"; then
    fail "$jar_path is missing the ServiceLoader entry $SERVICES_ENTRY"
  elif ! read_jar_entry "$jar_path" "$SERVICES_ENTRY" | grep -Fqx -- "$service_class"; then
    fail "$jar_path ServiceLoader entry does not declare $service_class"
  fi

  # No classes from other extension packages inside this jar.
  for other in "${EXTENSIONS[@]}"; do
    other_pkg="$(awk '{print $2}' <<<"$other")"
    if [ "$other_pkg" != "$pkg" ]; then
      if jar tf "$jar_path" | grep -Eq -- "^${EXT_ROOT}/${other_pkg}/"; then
        fail "$jar_path leaks classes from other extension package $other_pkg"
      fi
    fi
  done
done

echo "==> Checking for unexpected artifacts in build/libs/extensions"
find build/libs/extensions -maxdepth 1 -type f ! -name '*.jar' -print | while read -r stray; do
  echo "::warning::Stray file in build/libs/extensions: $stray"
done

if [ "$FAIL" -ne 0 ]; then
  echo "Artifact verification FAILED."
  exit 1
fi

echo "All ${#EXTENSIONS[@]} extension jars and the main plugin jar verified successfully."
