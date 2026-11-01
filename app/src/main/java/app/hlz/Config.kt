package app.hlz

object Config {
    // 1) Адрес твоего сайта на GitHub Pages (там лежит index.html). Обязательно со слэшем в конце.
    const val APP_URL = "https://grindal24-coder.github.io/Messanger/"

    // 2) Репозиторий, в «Releases» которого лежит APK для обновлений: "логин/репозиторий" (может быть тот же, что и у сайта)
    const val UPDATE_REPO = "grindal24-coder/Messanger"

    // 3) Данные Supabase (те же, что в index.html: URL и публичный anon-ключ). Нужны для приёма уведомлений.
    const val SUPABASE_URL = "https://rhznlpfnzyoawcmbuppz.supabase.co"
    const val SUPABASE_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InJoem5scGZuenlvYXdjbWJ1cHB6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTI5MzQ3NjQsImV4cCI6MjEwODUxMDc2NH0.jlIBlA-t1dM53I8EBY9FOiLxUFw9H1s7CUI-gP884nk"

    // Держать процессор «бодрствующим» для надёжной связи при погашенном экране (заметнее садит батарею). false — экономнее, но возможны задержки.
    const val KEEP_AWAKE = true
}
