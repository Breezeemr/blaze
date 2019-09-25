#!/bin/sh

set -e

ROOTDIR="$PWD"

build=
deploy=

gae_munge_version () {
    echo "$1" | tr '/._[:upper:]' '\-\-\-[:lower:]'
}

gae_validate_version () {
    # Valid google version specifier, see
    # https://cloud.google.com/appengine/docs/python/config/appconfig#version
    echo "$1" | grep --quiet --extended-regexp --null-data \
                     '^([a-z]|[a-z][a-z0-9\-]{0,61}[a-z0-9])$' -
    if [ $? -eq 0 -a "$1" != 'default' -a "$1" != 'latest' -a "${1#ah-}" = "$1" ] ;
    then
        echo "$1"
        return 0
    else
        echo "String is not valid google-app-engine version number: $1" >&2
        return 1
    fi
}

git_describe_branch_help=\
'git-describe-branch [-m MAIN_BRANCH=develop] [-gh]

Like git-describe, but may use branch names for the first segment.
Prints a version tag like REFERENCENAME[-#COMMITS]-gHASH[-dirty] where:

 * REFERENCENAME is either the last reachable annotated tag if the current
   branch is MAIN_BRANCH, otherwise the current branch name.
 * #COMMITS is the number of commits since either the point where the branch
   was created or the annotated tag. If would be '\''-0'\'', is ommitted.
   Assumes that branches are always branched from MAIN_BRANCH!
 * HASH is an abbreviated git hash
 * -dirty is shown if the commit is dirty.

Optional arguments:
 * -h Shows this help and exits
 * -m MAIN The main branch from which topic branches are created, default "develop"
 * -g Munge the result so it can be used as a google version id'\''s sake'

git_describe_branch () {
    local munge id main_branch branch_name ncommits hash dirty
    main_branch='develop'
    while getopts 'h:m:g' opt; do
        case $opt in
            h)  echo "$git_describe_branch_help" >&2
                return 1
                ;;
            m)  main_branch="$OPTARG"
                ;;
            g)  munge=1
                ;;
            \?) echo "$git_describe_branch_help"
                return 1
                ;;
        esac
    done

    # In detached head state we cannot use symbolic-ref. Jenkins scm runs in
    # detached head state. BRANCH_NAME is set by the multi-branch plugin, so we
    # use that instead if set.
    branch_name=$(git symbolic-ref -q HEAD || \
                  { test -n "$BRANCH_NAME" && echo "${BRANCH_NAME}" ; })
    branch_name=${branch_name#refs/heads/}
    if [ "$branch_name" != "$main_branch" ]; then
        # topic branch, assumed to have branched from main_branch
        ncommits="-$(git rev-list --count HEAD --not "refs/remotes/origin/$main_branch")"
        test "$ncommits" = '-0' && ncommits=""
        hash="-g$(git rev-parse --short HEAD)"

        git update-index -q --ignore-submodules --refresh
        { ! git diff-files --quiet --ignore-submodules -- || \
          ! git diff-index --cached --quiet HEAD --ignore-submodules -- ; } \
        && dirty="-dirty"

        id="${branch_name}${ncommits}${hash}${dirty}"
    else
        # main branch
        id="$(git describe --long --dirty)"
    fi

    if [ "$munge" ]; then
        gae_munge_version "$id"
    else
        echo "$id"
    fi
    return 0
}


# Requires that both working tree and index are clean
# Copied from http://stackoverflow.com/a/3879077
assert_clean_work_tree () {
    local err
    # Update the index
    git update-index -q --ignore-submodules --refresh
    err=0

    # Disallow unstaged changes in the working tree
    if ! git diff-files --quiet --ignore-submodules --
    then
        echo >&2 "cannot $1: you have unstaged changes."
        git diff-files --name-status -r --ignore-submodules -- >&2
        err=1
    fi

    # Disallow uncommitted changes in the index
    if ! git diff-index --cached --quiet HEAD --ignore-submodules --
    then
        echo >&2 "cannot $1: your index contains uncommitted changes."
        git diff-index --cached --name-status -r --ignore-submodules HEAD -- >&2
        err=1
    fi

    if [ $err = 1 ]
    then
        echo >&2 "Please commit or stash them."
        return 1
    fi
    return 0
}

require_clean_work_tree () {
    if [ -z "$ALLOW_DIRTY_TREE" ]; then
        assert_clean_work_tree
    fi
}

print_help () {
cat <<EOF >&2
build-app-engine.sh [build] [deploy] [version -gh -f feature -m MAIN] [help]

Build and/or deploy a production Jib to google app engine (main.breezeehr.com)

If run with 'version' as argument, prints unprefixed version string and exits.
'version' calls git_describe_branch internally and accepts its options. See
\`build-app-engine.sh version -h\` for help.

To allow building or deploying with uncommitted or unstaged changes, set the
environment variable ALLOW_DIRTY_TREE to something. E.g.:

    ALLOW_DIRTY_TREE=1 ./build-app-engine.sh build deploy
EOF
}

cleanup () {
    if [ "$?" -ne 0 ]; then
        echo "Encountered nonzero exit status: aborting." >&2
    fi
}

cmdopts () {
    if [ $# -eq 0 ]; then
        print_help
        return 0
    fi
    while [ $# -gt 0 ]; do
        case "$1" in
            build)
                build=1
                ;;
            deploy)
                deploy=1
                ;;
            version)
                shift
                git_describe_branch $@
                return 0
                ;;
            -h|help|-help|--help)
                print_help
                return 0
                ;;
            *)
                echo "Unknown option: '$1'" >&2
                return 1
                ;;
        esac
        shift
    done
}

do_build () {
    echo '#### BUILD (1/3): Building jib ####' >&2
    require_clean_work_tree
    APP_ENGINE_VERSION="jib-$(git_describe_branch -g)"
    cd "$ROOTDIR/jib"
    "$LEIN" build-prod
    cd "$ROOTDIR"
    echo '#### BUILD (2/3): Moving jib artifacts into jib-gae-war ####' >&2
    mkdir -p "$ROOTDIR/jib/dev-resources/sources"
    mv "$ROOTDIR"/jib/target/www/static/sourcemaps/*/source_*.zip "$ROOTDIR/jib/dev-resources/sources"
    rm -rf "$ROOTDIR/jib-gae/jib-gae-war/src/main/webapp/www/"
    mv jib/target/www jib-gae/jib-gae-war/src/main/webapp
    cd "$ROOTDIR/jib-gae"
    echo '#### BUILD (3/3): Building jib-gae ####' >&2
    "$MAVEN" -Dbreeze.jib.appengine-version="$APP_ENGINE_VERSION" clean install
    cd "$ROOTDIR"
    echo '#### BUILD (done) ####' >&2
}

do_deploy () {
    echo '#### DEPLOY (1/1) ####' >&2
    cd "$ROOTDIR/jib-gae/jib-gae-ear"
    "$MAVEN" appengine:update
    cd "$ROOTDIR"
    echo '#### DEPLOY (done) ####' >&2
}

## ENTRYPOINT
cmdopts $@

if [ -n "$build" -o -n "$deploy" ]; then
    MAVEN="${MAVEN:-$(which mvn3 || which mvn)}" || \
    { echo "Missing maven: did you install Maven >= 3.1?"; exit 1; }
    LEIN="${LEIN:-$(which lein)}" || \
    { echo "Missing lein: did you set \$LEIN or put it on the path?"; exit 1; }
fi

trap cleanup EXIT

test -n "$build" && do_build
test -n "$deploy" && do_deploy

exit 0

