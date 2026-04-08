package com.capstone.toma.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capstone.toma.ui.theme.*

@Composable
fun TomaTopAppBar(
    onMenuClick: () -> Unit = {},
    menuItems: List<TomaActionMenuItem> = emptyList()
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(TomaMainRed, CircleShape)
                )
            }

            Box(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "To-ma",
                    color = TomaBrown,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                TomaActionMenuButton(
                    items = menuItems,
                    iconTint = TomaPrimaryText
                )
            }
        }
    }
}