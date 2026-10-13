#!/bin/sh
# 안드로이드 CaptureService.PAGE(청취 페이지)를 그대로 떠서 LTBroadcast/Page.swift 로 만든다.
# 두 벌을 손으로 맞추면 반드시 어긋난다 — 원본은 안드로이드 쪽 하나만 둔다.
# Xcode 빌드 전에 매번 돈다(project.yml preBuildScripts).
set -e
cd "$(dirname "$0")/.."
SRC=../app/src/main/java/com/example/lt/CaptureService.java
OUT=LTBroadcast/Page.swift
{
  echo '// 자동 생성 — 고치지 말 것. 원본: app/.../CaptureService.java 의 PAGE. scripts/gen_page.sh'
  echo 'enum Page {'
  echo '    static let html = #"""'
  # Java 텍스트 블록의 \\ 는 실제로는 \ 한 글자다.
  awk '/private static final String PAGE = """/{f=1; next} f && /^""";/{exit} f' "$SRC" | sed 's/\\\\/\\/g'
  echo '"""#'
  echo '}'
} > "$OUT.tmp"
cmp -s "$OUT.tmp" "$OUT" 2>/dev/null && rm "$OUT.tmp" || mv "$OUT.tmp" "$OUT"
