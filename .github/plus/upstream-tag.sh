#!/usr/bin/env bash
# kdeconnect+：找出 cagedbird/plus 所基于的官方正式版本 tag，以及下一个要跟进的 tag。规则见 PLUS.md。
#
#   upstream-tag.sh base     输出当前基于的 tag，例如 v1.37.0
#   upstream-tag.sh select   输出 base=<当前 tag> 与 next=<下一个更新的 tag，没有则为空>（GITHUB_OUTPUT 格式）
#
# 只认 vX.Y.Z 形式的正式版本 tag。
set -euo pipefail

release_tags() {
	git tag --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V
}

base_tag() {
	local base=""
	for tag in $(release_tags); do
		if git merge-base --is-ancestor "$tag" HEAD; then
			base="$tag"
		fi
	done
	if [ -z "$base" ]; then
		echo "no upstream release tag is an ancestor of HEAD" >&2
		exit 1
	fi
	echo "$base"
}

case "${1:-}" in
	base)
		base_tag
		;;
	select)
		base="$(base_tag)"
		next="$(release_tags | sort -V | awk -v base="$base" 'found { print; exit } $0 == base { found = 1 }')"
		echo "base=$base"
		echo "next=$next"
		;;
	*)
		echo "usage: $0 base|select" >&2
		exit 2
		;;
esac
