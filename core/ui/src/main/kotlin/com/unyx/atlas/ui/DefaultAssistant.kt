package com.unyx.atlas.ui

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

object DefaultAssistant {
    fun isDefault(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
            return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val selected = Settings.Secure.getString(context.contentResolver, "assistant") ?: return false
        return ComponentName.unflattenFromString(selected)?.packageName == context.packageName
    }

    fun requestIntent(context: Context): Intent {
        val roles = context.getSystemService(RoleManager::class.java)
        if (roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
