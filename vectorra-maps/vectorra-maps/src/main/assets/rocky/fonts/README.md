`NotoSansSC-VF.ttf` provides the Android Rocky native label CJK glyph atlas.

The emulator's system CJK font is a TTC collection that ImGui/stbtt could not
parse in the current Android build. Keep a TTF/OTF CJK font in this assets
folder and point native `LabelStyle::fontName` at the copied runtime file under
`files/rocky_assets/fonts/`.
