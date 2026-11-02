package jp.cn22.yomipace

enum class ReaderScrollbarPosition(val label: String) {
    RIGHT("右"), LEFT("左"), NONE("なし");

    companion object {
        fun fromSaved(value: String?) = entries.firstOrNull { it.name == value } ?: LEFT
    }
}
