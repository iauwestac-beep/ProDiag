package ir.autodiagnostic.elm327

data class PidSpec(
    val pid: Int,
    val key: String,
    val title: String,
    val unit: String,
    val bytes: Int,
    val decode: (List<Int>) -> Double
)

data class LiveSnapshot(
    val timestampMillis: Long,
    val values: Map<String, String>
)

data class TroubleCode(
    val code: String,
    val category: String,
    val description: String,
    val state: String
)
