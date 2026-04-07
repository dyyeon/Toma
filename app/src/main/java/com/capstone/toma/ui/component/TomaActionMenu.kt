package com.capstone.toma.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.capstone.toma.ui.theme.TomaPrimaryText

data class TomaActionMenuItem(
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun TomaActionMenuButton(
    items: List<TomaActionMenuItem>,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    iconTint: Color = TomaPrimaryText
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        IconButton(
            onClick = {
                if (items.isNotEmpty()) {
                    expanded = true
                }
            },
            modifier = if (containerColor == Color.Transparent) {
                Modifier
            } else {
                Modifier.background(containerColor, CircleShape)
            }
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = iconTint
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White, RoundedCornerShape(18.dp))
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    onClick = {
                        expanded = false
                        item.onClick()
                    }
                )
            }
        }
    }
}
