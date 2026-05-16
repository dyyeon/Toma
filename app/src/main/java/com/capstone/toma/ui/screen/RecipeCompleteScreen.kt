package com.capstone.toma.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.ui.theme.TomaMainOrange
import com.capstone.toma.ui.theme.TomaPrimaryText
import com.capstone.toma.ui.theme.TomaSecondaryText
import com.capstone.toma.viewmodel.RecipeStorageViewModel
import org.json.JSONObject

@Composable
fun RecipeCompleteScreen(
    keyword: String,
    recipeDataJson: String?,
    sourceType: RecipeSourceType = RecipeSourceType.TEXT,
    onDoneClick: () -> Unit
) {
    val storageViewModel: RecipeStorageViewModel = viewModel()
    val recipeData = remember(recipeDataJson) {
        recipeDataJson?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    }
    val title = recipeData?.optString("title", keyword) ?: keyword
    val isFavorite by storageViewModel.isRecipeSaved(title).collectAsState(initial = false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(60.dp),
                    tint = TomaMainOrange
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "요리가 완성되었습니다!",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TomaPrimaryText
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "맛있는 $title 요리가 끝났네요.\n수고 많으셨습니다! 🧑‍🍳",
            fontSize = 16.sp,
            color = TomaSecondaryText,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        Spacer(modifier = Modifier.height(56.dp))

        // 즐겨찾기 유도 카드
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isFavorite) "즐겨찾기에 저장됨" else "이 레시피가 마음에 드셨나요?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TomaPrimaryText
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                IconButton(
                    onClick = { storageViewModel.toggleFavorite(title, recipeDataJson, isFavorite, sourceType) },
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            if (isFavorite) TomaMainOrange.copy(alpha = 0.1f) else Color(0xFFF1F3F5),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "즐겨찾기",
                        modifier = Modifier.size(40.dp),
                        tint = if (isFavorite) TomaMainOrange else Color.Gray
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isFavorite) "저장소에서 언제든 다시 볼 수 있어요" else "즐겨찾기에 추가하고 다음에 또 만들어보세요!",
                    fontSize = 13.sp,
                    color = TomaSecondaryText,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onDoneClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text("완료", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}
