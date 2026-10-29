#!/bin/bash
# The script sets up the sources of the Java standard library used by the symbol solver of the symbolic module: one
# folder per major Java version (`lib/java<version>/src`) and the list of their source folders
# (`lib/java_classpaths.json`).
# For each requested major version without a folder in `lib`, the script:
#   1. downloads the latest Eclipse Temurin JDK of that version from Adoptium (the build for Linux x64, whatever the
#      platform running the script: the sources are the same for every platform, apart from internal platform-specific
#      classes, and Linux x64 is the only platform with a build for every version)
#   2. extracts its `src.zip` into `lib/java<version>/src`, and its `release` file (the exact version of the build) into
#      `lib/java<version>/release`, then deletes the download
# If at least one version is added, the script regenerates `lib/java_classpaths.json` from the folders in `lib`.
#
# Usage: setup-jdk-sources.sh [options] [version...]
# Without versions, the script sets up every major version available from Adoptium.
# Options:
#   --list            print the major versions available from Adoptium and whether their sources are already in `lib`,
#                     without downloading anything
#   --lib <dir>       the folder of the sources (default: `lib` of the symbolic module)
#   --archive <file>  zip the folders of the sources and `java_classpaths.json` into the given file at the end (the
#                     archive downloaded by the init phase of `oracles-dataset.sh` and `tokens-dataset.sh`)

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"

ADOPTIUM_API="https://api.adoptium.net/v3"

lib_dir="${LIB_DIR}"
list_only=false
archive=""
versions=()
while [ $# -gt 0 ]; do
    case "$1" in
        --list) list_only=true ;;
        --lib) lib_dir="$2"; shift ;;
        --archive) archive="$2"; shift ;;
        [0-9]*) versions+=("$1") ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
    shift
done
mkdir -p "${lib_dir}"
lib_dir=$(realpath "${lib_dir}")
download_dir="${lib_dir}/.download"
trap 'rm -rf "${download_dir}"' EXIT

# Prints the major versions available from Adoptium, in ascending order
available_versions() {
    curl -s --max-time 60 "${ADOPTIUM_API}/info/available_releases" \
        | tr -d '\n ' \
        | grep -oE '"available_releases":\[[0-9,]*\]' \
        | grep -oE '[0-9]+' \
        | sort -n
}

# Downloads the JDK of a major version and extracts its sources into `lib/java<version>` (through a temporary folder,
# so that an interrupted run leaves no partial folder)
setup_version() {
    local version=$1
    local partial_dir="${lib_dir}/.java${version}.partial"
    local jdk_archive="${download_dir}/jdk${version}.tar.gz"
    local source_zip release
    if [ -d "${lib_dir}/java${version}" ]; then
        echo "Java ${version}: sources already in ${lib_dir}/java${version}"
        return 1
    fi
    echo "Java ${version}: downloading the JDK"
    rm -rf "${download_dir}" "${partial_dir}"
    mkdir -p "${download_dir}"
    if ! curl -fsSL --retry 3 -o "${jdk_archive}" "${ADOPTIUM_API}/binary/latest/${version}/ga/linux/x64/jdk/hotspot/normal/eclipse"; then
        echo "Java ${version}: download failed" >&2
        return 2
    fi
    # `src.zip` is at the root of the JDK up to Java 8, in `lib` since Java 9
    source_zip=$(tar -tzf "${jdk_archive}" | grep -E '^[^/]+/(lib/)?src\.zip$' | head -1)
    release=$(tar -tzf "${jdk_archive}" | grep -E '^[^/]+/release$' | head -1)
    if [ -z "${source_zip}" ]; then
        echo "Java ${version}: the JDK has no src.zip" >&2
        return 2
    fi
    echo "Java ${version}: extracting the sources"
    mkdir -p "${partial_dir}/src"
    if ! tar -xzf "${jdk_archive}" -C "${download_dir}" "${source_zip}" ${release} || ! unzip -q "${download_dir}/${source_zip}" -d "${partial_dir}/src"; then
        echo "Java ${version}: extraction failed" >&2
        rm -rf "${partial_dir}"
        return 2
    fi
    [ -n "${release}" ] && cp "${download_dir}/${release}" "${partial_dir}/release"
    mv "${partial_dir}" "${lib_dir}/java${version}"
    rm -rf "${download_dir}"
    echo "Java ${version}: sources in ${lib_dir}/java${version} ($(grep -m1 '^JAVA_RUNTIME_VERSION\|^JAVA_VERSION=' "${lib_dir}/java${version}/release" 2>/dev/null))"
    return 0
}

# Writes `java_classpaths.json`: for each folder `java<version>` in `lib`, the source folders given to the symbol
# solver (`src` up to Java 8, one folder per module since Java 9)
write_classpaths_json() {
    local json="${lib_dir}/java_classpaths.json"
    local versions_in_lib version entries entry first_version first_entry
    versions_in_lib=$(ls -d "${lib_dir}"/java[0-9]*/src 2>/dev/null | sed -E 's|.*/java([0-9]+)/src$|\1|' | sort -n)
    {
        echo "{"
        first_version=true
        for version in ${versions_in_lib}; do
            [ "${first_version}" == true ] || echo ","
            first_version=false
            if [ -f "${lib_dir}/java${version}/src/java/lang/Object.java" ]; then
                entries="src"
            else
                entries=$(cd "${lib_dir}/java${version}" && ls -d src/*/ | sed 's|/$||' | sort)
            fi
            printf '  "%s":[' "${version}"
            first_entry=true
            for entry in ${entries}; do
                [ "${first_entry}" == true ] || printf ','
                first_entry=false
                printf '\n    "%s"' "${entry}"
            done
            printf '\n  ]'
        done
        printf '\n}\n'
    } > "${json}.tmp" && mv "${json}.tmp" "${json}"
}

if [ ${#versions[@]} -eq 0 ]; then
    versions=($(available_versions))
    if [ ${#versions[@]} -eq 0 ]; then
        echo "Unable to read the list of the versions available from Adoptium" >&2
        exit 1
    fi
fi

if [ "${list_only}" == true ]; then
    for version in "${versions[@]}"; do
        status="missing"
        [ -d "${lib_dir}/java${version}" ] && status="in lib"
        printf 'Java %-3s %s\n' "${version}" "${status}"
    done
    exit 0
fi

added=0
failures=0
for version in "${versions[@]}"; do
    setup_version "${version}"
    case $? in
        0) added=$((added + 1)) ;;
        2) failures=$((failures + 1)) ;;
    esac
done
if [ ${added} -gt 0 ] || [ ! -f "${lib_dir}/java_classpaths.json" ]; then
    write_classpaths_json
    echo "Written ${lib_dir}/java_classpaths.json"
fi
if [ -n "${archive}" ]; then
    archive=$(realpath "$(dirname "${archive}")")/$(basename "${archive}")
    rm -f "${archive}"
    (cd "${lib_dir}" && zip -qr "${archive}" java_classpaths.json java[0-9]* -x '*.DS_Store') && echo "Written ${archive}"
fi
exit ${failures}
