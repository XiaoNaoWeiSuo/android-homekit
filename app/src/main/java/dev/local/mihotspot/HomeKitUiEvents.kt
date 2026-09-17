package dev.local.mihotspot

/** Private in-app broadcasts used to keep the Activity in sync with the service. */
object HomeKitUiEvents {
    const val ACTION_RUNTIME_STATUS = "dev.local.mihotspot.action.RUNTIME_STATUS"
    const val ACTION_STATE_CHANGED = "dev.local.mihotspot.action.STATE_CHANGED"
    const val EXTRA_PAIRED = "paired"
    const val EXTRA_CONNECTED = "connected"
    const val EXTRA_ADVERTISING = "advertising"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_IS_INTEGER = "is_integer"
    const val EXTRA_BOOLEAN_VALUE = "boolean_value"
    const val EXTRA_INTEGER_VALUE = "integer_value"
}
