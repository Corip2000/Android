override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    // Свой выбор файлов: стандартный createIntent() на многих
                    // прошивках возвращает пустой результат, и фото не отправляются
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val types = params?.acceptTypes
                            ?.filter { it.isNotBlank() }
                            ?.toTypedArray()
                        if (!types.isNullOrEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types)
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }
                    filePicker.launch(Intent.createChooser(pick, "Выберите файл"))
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
}
