package id.dumper.parti.data.model

sealed class RootState {
    object Checking : RootState()
    object Granted : RootState()
    data class Denied(val message: String) : RootState()
}
