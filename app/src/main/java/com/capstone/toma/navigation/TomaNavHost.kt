package com.capstone.toma.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import androidx.navigation.NavType
import com.capstone.toma.PublicRecipeManager
import com.capstone.toma.TomaIntent
import com.capstone.toma.UserManager
import com.capstone.toma.WebPageManager
import com.capstone.toma.YoutubeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.model.toRecipeDataJson
import org.json.JSONObject
import com.capstone.toma.ui.screen.*
import com.capstone.toma.viewmodel.*

/** Returns true for any YouTube URL including Shorts (/shorts/), standard (/watch?v=), and youtu.be. */
private fun String.isYoutubeUrl(): Boolean =
    contains("youtube.com/watch") ||
    contains("youtube.com/shorts/") ||
    contains("youtu.be/") ||
    contains("youtube.com/embed/")

private val voiceSuggestions = listOf(
    "오늘 저녁 뭐 먹을까",
    "냉장고 재료로 뭐 해먹지",
    "간단한 한 끼 추천해줘",
    "초보도 쉬운 레시피",
    "10분 안에 만드는 요리",
    "자취생 요리 알려줘"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomaNavHost(
    navController: NavHostController = rememberNavController()
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeUiState by homeViewModel.uiState.collectAsState()

    val chatViewModel: ChatViewModel = viewModel()
    val chatUiState by chatViewModel.uiState.collectAsState()

    val voiceViewModel: VoiceViewModel = viewModel()
    val voiceUiState by voiceViewModel.uiState.collectAsState()

    val context = LocalContext.current

    var showImageSourceSheet by remember { mutableStateOf(false) }
    // Holds the File and URI created before launching TakePicture so the callback can reference them
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var pendingCameraUri  by remember { mutableStateOf<Uri?>(null) }

    // Helper: create a fresh timestamped file under cacheDir/camera/ and return its FileProvider URI.
    // The directory is created eagerly; TakePicture writes the JPEG content into the file.
    fun prepareCameraUri(): Pair<File, Uri> {
        val dir  = File(context.cacheDir, "camera").also { it.mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    // Declared first so the permission launcher can reference it
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri  = pendingCameraUri
        val file = pendingCameraFile
        pendingCameraUri  = null
        pendingCameraFile = null

        if (success && uri != null) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != TomaDestination.Chat.route) {
                chatViewModel.resetChat()
                navController.navigate(TomaDestination.Chat.route)
            }
            chatViewModel.startLinkAnalysis(
                userDisplay   = "카메라로 레시피 찾기",
                initialAiText = "사진을 분석하고 있어요. 잠시만 기다려 주세요... 📸",
                fixedSourceType = com.capstone.toma.model.RecipeSourceType.IMAGE
            ) { _ ->
                com.capstone.toma.OpenAiManager().analyzeRecipeImageSuspend(context, uri.toString())
            }
        } else {
            // User cancelled — delete the pre-allocated temp file
            file?.delete()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val (file, uri) = prepareCameraUri()
            pendingCameraFile = file
            pendingCameraUri  = uri
            cameraLauncher.launch(uri)
        } else {
            homeViewModel.showError("카메라 권한이 필요해요. 설정에서 허용해 주세요.", isDialog = true)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != TomaDestination.Chat.route) {
                chatViewModel.resetChat()
                navController.navigate(TomaDestination.Chat.route)
            }
            chatViewModel.startLinkAnalysis(
                userDisplay = "사진으로 레시피 찾기",
                initialAiText = "사진을 분석하고 있어요. 잠시만 기다려 주세요... 📸",
                fixedSourceType = com.capstone.toma.model.RecipeSourceType.IMAGE
            ) { _ ->
                val openAi = com.capstone.toma.OpenAiManager()
                openAi.analyzeRecipeImageSuspend(context, uri.toString())
            }
        } else {
            homeViewModel.showError("이미지를 선택하지 않았어요.")
        }
    }

    // ModalBottomSheet uses an internal Popup layer, so it renders above NavHost regardless of order
    if (showImageSourceSheet) {
        ImageSourceBottomSheet(
            onDismiss = { showImageSourceSheet = false },
            onCameraSelected = {
                showImageSourceSheet = false
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) {
                    val (file, uri) = prepareCameraUri()
                    pendingCameraFile = file
                    pendingCameraUri  = uri
                    cameraLauncher.launch(uri)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onGallerySelected = {
                showImageSourceSheet = false
                imagePickerLauncher.launch("image/*")
            }
        )
    }

    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            LaunchedEffect(Unit) {
                voiceViewModel.stopWakeWord()
                val enrolled = UserManager.isEnrolled(context)
                val skipped = UserManager.hasSkipped(context)
                // Mandatory enrollment check: only proceed to Home if enrolled or skipped
                if (!enrolled && !skipped) {
                    navController.navigate(TomaDestination.SpeakerEnrollment.route) {
                        popUpTo(TomaDestination.Home.route) { inclusive = false }
                    }
                } else if (enrolled) {
                    val modelFile = java.io.File(context.filesDir, "hey_toma_personal.onnx")
                    if (!modelFile.exists()) {
                        voiceViewModel.startModelPolling(context)
                    }
                }
                homeViewModel.refreshRecentItems()
            }

            TomaHomeScreen(
                uiState = homeUiState,
                onSearchQueryChange = homeViewModel::updateSearchQuery,
                onSearchSubmit = {
                    val query = homeUiState.searchQuery
                    if (query.isNotBlank()) {
                        val isValidUrl = query.startsWith("http://") || query.startsWith("https://")
                        if (isValidUrl) {
                            val isYoutube = query.isYoutubeUrl()
                            val webPageManager = WebPageManager()
                            val youtubeManager = YoutubeManager()
                            chatViewModel.resetChat()
                            navController.navigate(TomaDestination.Chat.route)

                            val displayMsg = if (isYoutube) "유튜브 분석 중: $query" else "웹 레시피 분석 중: $query"

                            chatViewModel.startLinkAnalysis(
                                userDisplay = displayMsg,
                                initialAiText = "페이지 본문을 읽어오고 있어요... 📄",
                                fixedSourceType = if (isYoutube) com.capstone.toma.model.RecipeSourceType.YOUTUBE else com.capstone.toma.model.RecipeSourceType.WEB
                            ) { updateStatus ->
                                val (t, d, img) = if (isYoutube) {
                                    youtubeManager.fetchVideoInfoSuspend(query)
                                } else {
                                    webPageManager.fetchPageInfoSuspend(query)
                                }

                                val fetchFailed = t == null && (d == null
                                        || d.startsWith("본문을 읽어오는데 실패")
                                        || d.startsWith("페이지 로드 실패")
                                        || d.startsWith("유튜브 정보를 가져오는데 실패")
                                        || d.startsWith("유튜브 정보를 로드할 수 없습니다"))
                                if (fetchFailed) {
                                    homeViewModel.showError("링크를 읽어올 수 없어요. 다시 시도해주세요.", isDialog = true)
                                    return@startLinkAnalysis VoiceRequestResult.Error("스크래핑 실패")
                                }

                                val isLikelyRecipe = t?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true
                                        || d?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true

                                if (isLikelyRecipe) {
                                    updateStatus("전문가 AI가 실전 정보를 추출 중이에요... ✨")
                                    delay(400)
                                } else {
                                    updateStatus("전문가 AI가 내용을 분석하고 있어요... ✨")
                                    delay(100)
                                }

                                val openAi = com.capstone.toma.OpenAiManager()
                                val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }

                                val prompt = if (isYoutube) {
                                    val cleanTitle = t?.replace(Regex("[^가-힣a-zA-Z0-9 ]"), " ")?.trim() ?: ""
                                    """
                                    다음 유튜브 영상 제목을 보고 요리/레시피 영상인지 먼저 판단하세요.
                                    영상 제목: ${t ?: ""}

                                    요리/레시피 영상이 맞다면 레시피를 작성해주세요:
                                    - 요리명: $cleanTitle
                                    - 재료명, 단계, 모든 내용을 반드시 한국어로만 작성하세요. 영어 사용 금지.
                                    - 조리 단계는 각 단계를 하나의 동작으로 나눠서 7단계 이상 상세하게 작성하세요.
                                    - 불 세기, 시간, 조리 방법, 완성 기준을 구체적으로 포함하세요.

                                    음악, 게임, 드라마, 뉴스 등 요리와 관련 없는 영상이라면 type을 'not_recipe'로 설정하세요.
                                    """.trimIndent()
                                } else {
                                    """
                                    다음 웹페이지 내용을 분석해서 레시피 정보를 추출해주세요.
                                    이 페이지가 요리/레시피와 관련 없다면 type을 'not_recipe'로 설정하세요.
                                    URL: $query
                                    제목: ${t ?: "제목 없음"}
                                    내용:
                                    $d
                                    """.trimIndent()
                                }

                                val aiResult = openAi.processChatRequestSuspend(prompt, history)
                                if (aiResult is VoiceRequestResult.Success && aiResult.requestType == "not_recipe") {
                                    return@startLinkAnalysis VoiceRequestResult.Success(
                                        requestType = "not_recipe",
                                        keyword = "",
                                        responseMessage = "이 링크는 요리 레시피가 아닌 것 같아요 \n\n요리 관련 페이지 링크를 다시 입력해주시거나, 음식 이름을 채팅으로 직접 알려주세요!",
                                        recipeData = null
                                    )
                                }
                                if (aiResult is VoiceRequestResult.Success && aiResult.recipeData != null) {
                                    val recipeJson = JSONObject(aiResult.recipeData)
                                    val currentImageUrl = recipeJson.optString("image_url")
                                    if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
                                        val imageUrl = img ?: withContext(Dispatchers.IO) {
                                            PublicRecipeManager().searchRecipe(aiResult.keyword)?.mainImageUrl?.takeIf { it.isNotBlank() }
                                                ?: WebPageManager().searchFoodImage(aiResult.keyword)
                                        }
                                        if (imageUrl != null) {
                                            recipeJson.put("image_url", imageUrl)
                                            aiResult.copy(recipeData = recipeJson.toString())
                                        } else aiResult
                                    } else aiResult
                                } else aiResult
                            }

                            homeViewModel.updateSearchQuery("")
                        } else {
                            chatViewModel.resetChat()
                            chatViewModel.sendMessage(query)
                            navController.navigate(TomaDestination.Chat.route)
                            homeViewModel.updateSearchQuery("")
                        }
                    }
                },
                onMicClick = {
                    navController.navigateSingleTop(TomaDestination.VoiceGuide.route)
                },
                onYoutubeLinkChange = homeViewModel::updateYoutubeLink,
                onYoutubeSubmit = {
                    val link = homeUiState.youtubeLink

                    if (link.isNotBlank()) {
                        val isYoutube = link.isYoutubeUrl()
                        val isValidUrl = link.startsWith("http://") || link.startsWith("https://")

                        if (isValidUrl) {
                            val webPageManager = WebPageManager()
                            val youtubeManager = YoutubeManager()
                            chatViewModel.resetChat()
                            navController.navigate(TomaDestination.Chat.route)

                            val displayMsg =
                                if (isYoutube) "유튜브 분석 중: $link"
                                else "웹 레시피 분석 중: $link"

                            chatViewModel.startLinkAnalysis(
                                userDisplay = displayMsg,
                                initialAiText = "페이지 본문을 읽어오고 있어요... 📄",
                                fixedSourceType = if (isYoutube) com.capstone.toma.model.RecipeSourceType.YOUTUBE else com.capstone.toma.model.RecipeSourceType.WEB
                            ) { updateStatus ->
                                val (t, d, img) = if (isYoutube) {
                                    youtubeManager.fetchVideoInfoSuspend(link)
                                } else {
                                    webPageManager.fetchPageInfoSuspend(link)
                                }

                                val fetchFailed = t == null && (d == null
                                        || d.startsWith("본문을 읽어오는데 실패")
                                        || d.startsWith("페이지 로드 실패")
                                        || d.startsWith("유튜브 정보를 가져오는데 실패")
                                        || d.startsWith("유튜브 정보를 로드할 수 없습니다"))
                                if (fetchFailed) {
                                    homeViewModel.showError("링크를 읽어올 수 없어요. 다시 시도해주세요.", isDialog = true)
                                    return@startLinkAnalysis VoiceRequestResult.Error("스크래핑 실패")
                                }

                                val isLikelyRecipe = t?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true
                                        || d?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true

                                if (isLikelyRecipe) {
                                    updateStatus("전문가 AI가 실전 정보를 추출 중이에요... ✨")
                                    delay(400)
                                } else {
                                    updateStatus("전문가 AI가 내용을 분석하고 있어요... ✨")
                                    delay(100)
                                }

                                val openAi = com.capstone.toma.OpenAiManager()
                                val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }

                                val prompt = if (isYoutube) {
                                    val cleanTitle = t?.replace(Regex("[^가-힣a-zA-Z0-9 ]"), " ")?.trim() ?: ""
                                    """
                                    다음 유튜브 영상 제목을 보고 요리/레시피 영상인지 먼저 판단하세요.
                                    영상 제목: ${t ?: ""}

                                    요리/레시피 영상이 맞다면 레시피를 작성해주세요:
                                    - 요리명: $cleanTitle
                                    - 재료명, 단계, 모든 내용을 반드시 한국어로만 작성하세요. 영어 사용 금지.
                                    - 조리 단계는 각 단계를 하나의 동작으로 나눠서 7단계 이상 상세하게 작성하세요.
                                    - 불 세기, 시간, 조리 방법, 완성 기준을 구체적으로 포함하세요.

                                    음악, 게임, 드라마, 뉴스 등 요리와 관련 없는 영상이라면 type을 'not_recipe'로 설정하세요.
                                    """.trimIndent()
                                } else {
                                    """
                                    다음 웹페이지 내용을 분석해서 레시피 정보를 추출해주세요.
                                    이 페이지가 요리/레시피와 관련 없다면 type을 'not_recipe'로 설정하세요.
                                    URL: $link
                                    제목: ${t ?: "제목 없음"}
                                    내용:
                                    $d
                                    """.trimIndent()
                                }

                                val aiResult = openAi.processChatRequestSuspend(prompt, history)
                                if (aiResult is VoiceRequestResult.Success && aiResult.requestType == "not_recipe") {
                                    return@startLinkAnalysis VoiceRequestResult.Success(
                                        requestType = "not_recipe",
                                        keyword = "",
                                        responseMessage = "이 링크는 요리 레시피가 아닌 것 같아요 \n\n요리 관련 페이지 링크를 다시 입력해주시거나, 음식 이름을 채팅으로 직접 알려주세요!",
                                        recipeData = null
                                    )
                                }
                                if (aiResult is VoiceRequestResult.Success && aiResult.recipeData != null) {
                                    val recipeJson = JSONObject(aiResult.recipeData)
                                    val currentImageUrl = recipeJson.optString("image_url")
                                    if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
                                        val imageUrl = img ?: withContext(Dispatchers.IO) {
                                            PublicRecipeManager().searchRecipe(aiResult.keyword)?.mainImageUrl?.takeIf { it.isNotBlank() }
                                                ?: WebPageManager().searchFoodImage(aiResult.keyword)
                                        }
                                        if (imageUrl != null) {
                                            recipeJson.put("image_url", imageUrl)
                                            aiResult.copy(recipeData = recipeJson.toString())
                                        } else aiResult
                                    } else aiResult
                                } else aiResult
                            }

                            homeViewModel.updateYoutubeLink("")
                        } else {
                            homeViewModel.showError("올바른 링크를 입력해주세요.")
                        }
                    }
                },
                onPhotoScanClick = { showImageSourceSheet = true },
                onRecentItemClick = { itemId ->
                    val item = homeUiState.recentItems.find { it.id == itemId }
                    item?.let {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(it.title, it.sourceType, it.recipeDataJson)
                        )
                    }
                },
                onRecentMoreClick = {
                    navController.navigateSingleTop(TomaDestination.RecentHistory.route)
                },
                onStorageClick = {
                    navController.navigateSingleTop(TomaDestination.RecipeStorage.route)
                },
                onSettingsClick = {
                    navController.navigateSingleTop(TomaDestination.Settings.route)
                },
                onPrivacyPolicyClick = {
                    navController.navigateSingleTop(TomaDestination.PrivacyPolicy.route)
                },
                onSpeakerEnrollmentClick = {
                    navController.navigateSingleTop(TomaDestination.SpeakerEnrollment.route)
                },
                onErrorDismiss = homeViewModel::clearError
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(
                onBackClick = { navController.popBackStack() },
                onStartGuide = { recipe ->
                    val recipeData = recipe.toRecipeDataJson()
                    homeViewModel.saveRecentRecipe(
                        keyword = recipe.title,
                        recipeDataJson = recipeData,
                        sourceType = recipe.sourceType
                    )
                    navController.navigate(
                        TomaDestination.RecipeDetail.createRoute(
                            recipe.title,
                            recipe.sourceType,
                            recipeData
                        )
                    )
                }
            )
        }

        composable(TomaDestination.RecentHistory.route) {
            LaunchedEffect(Unit) {
                homeViewModel.refreshRecentItems()
            }

            RecentHistoryScreen(
                items = homeUiState.recentItems,
                onBackClick = { navController.popBackStack() },
                onItemClick = { item ->
                    navController.navigate(
                        TomaDestination.RecipeDetail.createRoute(item.title, item.sourceType, item.recipeDataJson)
                    )
                }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            LaunchedEffect(Unit) {
                voiceViewModel.intentEvent.collect { intent ->
                    if (intent is TomaIntent.RECIPE_SEARCH) {
                        voiceViewModel.stopListeningManually()
                        chatViewModel.resetChat()
                        chatViewModel.sendMessage(intent.keyword)
                        navController.navigate(TomaDestination.Chat.route) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    }
                }
            }
            VoiceGuideScreen(
                uiState = voiceUiState,
                suggestions = voiceSuggestions,
                onMicClick = {
                    when (voiceUiState) {
                        is com.capstone.toma.VoiceUiState.Idle -> voiceViewModel.startListeningManually()
                        else -> voiceViewModel.stopListeningManually()
                    }
                },
                onSuggestionClick = { text ->
                    voiceViewModel.stopListeningManually()
                    chatViewModel.resetChat()
                    chatViewModel.sendMessage(text)
                    navController.navigate(TomaDestination.Chat.route) {
                        popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                    }
                },
                onBackClick = {
                    voiceViewModel.stopListeningManually()
                    navController.popBackStack()
                }
            )
        }

        composable(TomaDestination.Chat.route) {
            LaunchedEffect(Unit) {
                voiceViewModel.stopWakeWord()
            }
            val navEvent by chatViewModel.navigationEvent.collectAsState()
            val errorEvent by chatViewModel.errorEvent.collectAsState()

            LaunchedEffect(navEvent) {
                when (val event = navEvent) {
                    is ChatNavigationEvent.ToConfirm -> {
                        navController.navigate(
                            TomaDestination.RecipeConfirm.createRoute(
                                event.keyword,
                                event.sourceType,
                                event.recipeData
                            )
                        )
                        chatViewModel.clearNavigationEvent()
                    }
                    is ChatNavigationEvent.ToDetail -> {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(
                                event.keyword,
                                event.sourceType,
                                event.recipeData
                            )
                        )
                        chatViewModel.clearNavigationEvent()
                    }
                    null -> Unit
                }
            }

            LaunchedEffect(errorEvent) {
                errorEvent?.let { message ->
                    android.widget.Toast.makeText(
                        context,
                        "분석 실패: $message",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    chatViewModel.clearErrorEvent()
                }
            }

            AiChatScreen(
                uiState = chatUiState,
                onBackClick = { navController.popBackStack() },
                onInputTextChange = chatViewModel::onInputTextChange,
                onAddImageClick = { showImageSourceSheet = true },
                onSendMessage = {
                    val inputText = chatUiState.inputText.trim()
                    val isUrl = inputText.startsWith("http://") || inputText.startsWith("https://")
                    if (isUrl) {
                        val isYoutube = inputText.isYoutubeUrl()
                        val webPageManager = WebPageManager()
                        val youtubeManager = YoutubeManager()
                        chatViewModel.onInputTextChange("")

                        chatViewModel.startLinkAnalysis(
                            userDisplay = inputText,
                            initialAiText = "페이지 본문을 읽어오고 있어요... 📄",
                            fixedSourceType = if (isYoutube) com.capstone.toma.model.RecipeSourceType.YOUTUBE else com.capstone.toma.model.RecipeSourceType.WEB
                        ) { updateStatus ->
                            val (t, d, img) = if (isYoutube) youtubeManager.fetchVideoInfoSuspend(inputText) else webPageManager.fetchPageInfoSuspend(inputText)

                            val fetchFailed = t == null && (d == null
                                    || d.startsWith("본문을 읽어오는데 실패")
                                    || d.startsWith("페이지 로드 실패")
                                    || d.startsWith("유튜브 정보를 가져오는데 실패")
                                    || d.startsWith("유튜브 정보를 로드할 수 없습니다"))
                            if (fetchFailed) {
                                return@startLinkAnalysis VoiceRequestResult.Success(
                                    requestType = "not_recipe",
                                    keyword = "",
                                    responseMessage = "링크를 읽어올 수 없어요 \n다시 시도하거나 음식 이름을 직접 알려주세요!",
                                    recipeData = null
                                )
                            }

                            val isLikelyRecipe = t?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true
                                    || d?.contains(Regex("요리|레시피|음식|맛|먹|식|재료|조리|간식|반찬|안주")) == true

                            if (isLikelyRecipe) {
                                updateStatus("전문가 AI가 실전 정보를 추출 중이에요... ✨")
                                delay(400)
                            } else {
                                updateStatus("전문가 AI가 내용을 분석하고 있어요... ✨")
                                delay(100)
                            }

                            val openAi = com.capstone.toma.OpenAiManager()
                            val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }

                            val prompt = if (isYoutube) {
                                val cleanTitle = t?.replace(Regex("[^가-힣a-zA-Z0-9 ]"), " ")?.trim() ?: ""
                                """
                                다음 유튜브 영상 제목을 보고 요리/레시피 영상인지 먼저 판단하세요.
                                영상 제목: ${t ?: ""}

                                요리/레시피 영상이 맞다면 레시피를 작성해주세요:
                                - 요리명: $cleanTitle
                                - 재료명, 단계, 모든 내용을 반드시 한국어로만 작성하세요. 영어 사용 금지.
                                - 조리 단계는 각 단계를 하나의 동작으로 나눠서 7단계 이상 상세하게 작성하세요.
                                - 불 세기, 시간, 조리 방법, 완성 기준을 구체적으로 포함하세요.

                                음악, 게임, 드라마, 뉴스 등 요리와 관련 없는 영상이라면 type을 'not_recipe'로 설정하세요.
                                """.trimIndent()
                            } else {
                                """
                                다음 웹페이지 내용을 분석해서 레시피 정보를 추출해주세요.
                                이 페이지가 요리/레시피와 관련 없다면 type을 'not_recipe'로 설정하세요.
                                URL: $inputText
                                제목: ${t ?: "제목 없음"}
                                내용:
                                $d
                                """.trimIndent()
                            }

                            val aiResult = openAi.processChatRequestSuspend(prompt, history)
                            if (aiResult is VoiceRequestResult.Success && aiResult.requestType == "not_recipe") {
                                return@startLinkAnalysis VoiceRequestResult.Success(
                                    requestType = "not_recipe",
                                    keyword = "",
                                    responseMessage = "이 링크는 요리 레시피가 아닌 것 같아요 \n\n요리 관련 페이지 링크를 다시 입력해주시거나, 음식 이름을 채팅으로 직접 알려주세요!",
                                    recipeData = null
                                )
                            }
                            if (aiResult is VoiceRequestResult.Success && aiResult.recipeData != null) {
                                val recipeJson = JSONObject(aiResult.recipeData)
                                val currentImageUrl = recipeJson.optString("image_url")
                                if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
                                    val imageUrl = img ?: withContext(Dispatchers.IO) {
                                        PublicRecipeManager().searchRecipe(aiResult.keyword)?.mainImageUrl?.takeIf { it.isNotBlank() }
                                            ?: WebPageManager().searchFoodImage(aiResult.keyword)
                                    }
                                    if (imageUrl != null) {
                                        recipeJson.put("image_url", imageUrl)
                                        aiResult.copy(recipeData = recipeJson.toString())
                                    } else aiResult
                                } else aiResult
                            } else aiResult
                        }
                    } else {
                        chatViewModel.sendMessage()
                    }
                },
                onErrorDismiss = chatViewModel::clearErrorEvent
            )
        }

        composable(TomaDestination.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onPushClick = { navController.navigate(TomaDestination.PushSetting.route) },
                onEmailClick = { navController.navigate(TomaDestination.EmailSetting.route) },
                onCustomerCenterClick = { navController.navigate(TomaDestination.CustomerCenter.route) },
                onContactClick = { navController.navigate(TomaDestination.ContactUs.route) },
                onSpeakerEnrollmentClick = { navController.navigate(TomaDestination.SpeakerEnrollment.route) }
            )
        }

        composable(TomaDestination.PushSetting.route) { PushSettingScreen(onBackClick = { navController.popBackStack() }) }
        composable(TomaDestination.EmailSetting.route) { EmailSettingScreen(onBackClick = { navController.popBackStack() }) }
        composable(TomaDestination.CustomerCenter.route) { CustomerCenterScreen(onBackClick = { navController.popBackStack() }) }
        composable(TomaDestination.ContactUs.route) { ContactUsScreen(onBackClick = { navController.popBackStack() }) }
        composable(TomaDestination.PrivacyPolicy.route) { PrivacyPolicyScreen(onBackClick = { navController.popBackStack() }) }

        composable(TomaDestination.SpeakerEnrollment.route) {
            SpeakerEnrollmentScreen(
                voiceViewModel = voiceViewModel,
                onEnrollmentComplete = {
                    navController.popBackStack()
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = TomaDestination.RecipeConfirm.route,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType },
                navArgument("sourceType") { type = NavType.StringType },
                navArgument("recipeData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
            val sourceTypeStr = backStackEntry.arguments?.getString("sourceType") ?: "TEXT"
            val sourceType = com.capstone.toma.model.RecipeSourceType.valueOf(sourceTypeStr)
            val recipeData = backStackEntry.arguments?.getString("recipeData")
            RecipeConfirmScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() },
                onConfirmClick = {
                    homeViewModel.saveRecentRecipe(
                        keyword = keyword,
                        recipeDataJson = recipeData,
                        sourceType = sourceType
                    )
                    navController.navigate(TomaDestination.RecipeDetail.createRoute(keyword, sourceType, recipeData)) {
                        popUpTo(TomaDestination.RecipeConfirm.route) { inclusive = true }
                    }
                },
                onRejectClick = { navController.popBackStack() }
            )
        }

        composable(
            route = TomaDestination.RecipeDetail.route,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType },
                navArgument("sourceType") { type = NavType.StringType },
                navArgument("recipeData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
            val sourceTypeStr = backStackEntry.arguments?.getString("sourceType") ?: "TEXT"
            val sourceType = com.capstone.toma.model.RecipeSourceType.valueOf(sourceTypeStr)
            val recipeData = backStackEntry.arguments?.getString("recipeData")
            RecipeDetailScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() },
                onFinish = { kw, data ->
                    navController.navigate(TomaDestination.RecipeComplete.createRoute(kw, data)) {
                        popUpTo(TomaDestination.RecipeDetail.route) { inclusive = true }
                    }
                },
                voiceViewModel = voiceViewModel
            )
        }

        composable(
            route = TomaDestination.RecipeComplete.route,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType },
                navArgument("recipeData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
            val recipeData = backStackEntry.arguments?.getString("recipeData")
            RecipeCompleteScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onDoneClick = {
                    navController.popBackStack(TomaDestination.Home.route, false)
                }
            )
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSourceBottomSheet(
    onDismiss: () -> Unit,
    onCameraSelected: () -> Unit,
    onGallerySelected: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "이미지 가져오기",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = Color(0xFF212529)
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Camera option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCameraSelected() },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFF1E8),
                border = BorderStroke(1.dp, Color(0xFFEE8C2B).copy(alpha = 0.25f)),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color(0xFFEE8C2B)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "카메라로 촬영",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF212529)
                        )
                        Text(
                            text = "지금 바로 사진을 찍어 분석해요",
                            fontSize = 13.sp,
                            color = Color(0xFF868E96)
                        )
                    }
                }
            }

            // Gallery option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onGallerySelected() },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF8F9FA),
                border = BorderStroke(1.dp, Color(0xFFF1F3F5)),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = Color(0xFF868E96)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "갤러리에서 선택",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF212529)
                        )
                        Text(
                            text = "앨범에서 사진을 골라 분석해요",
                            fontSize = 13.sp,
                            color = Color(0xFF868E96)
                        )
                    }
                }
            }
        }
    }
}
