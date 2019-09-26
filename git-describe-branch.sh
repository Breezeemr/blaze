gae_munge_version () {
    echo "$1" | tr '/._[:upper:]' '\-\-\-[:lower:]'
}

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

git_describe_branch -g
