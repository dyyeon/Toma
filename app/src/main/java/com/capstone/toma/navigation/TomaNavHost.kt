package com.capstone.toma.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import org.json.JSONObject
import com.capstone.toma.ui.screen.*
import com.capstone.toma.viewmodel.*

private val voiceSuggestions = listOf(
    "오늘 저녁 뭐 먹을까",
    "냉장고 재료로 뭐 해먹지",
    "간단한 한 끼 추천해줘",
    "초보도 쉬운 레시피",
    "10분 안에 만드는 요리",
    "자취생 요리 알려줘"
)

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

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            chatViewModel.resetChat()
            navController.navigate(TomaDestination.Chat.route)
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

    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            LaunchedEffect(Unit) {
                voiceViewModel.stopWakeWord()
                val enrolled = UserManager.isEnrolled(context)
                val skipped = UserManager.hasSkipped(context)
                if (!enrolled && skipped.not()) {
                    navController.navigate(TomaDestination.SpeakerEnrollment.route)
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
                            val isYoutube = query.contains("youtube.com") || query.contains("youtu.be")
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
                        val isYoutube = link.contains("youtube.com") || link.contains("youtu.be")
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
                onPhotoScanClick = { imagePickerLauncher.launch("image/*") },
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
                onErrorDismiss = homeViewModel::clearError
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(onBackClick = { navController.popBackStack() })
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
                onSendMessage = {
                    val inputText = chatUiState.inputText.trim()
                    val isUrl = inputText.startsWith("http://") || inputText.startsWith("https://")
                    if (isUrl) {
                        val isYoutube = inputText.contains("youtube.com") || inputText.contains("youtu.be")
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
                onContactClick = { navController.navigate(TomaDestination.ContactUs.route) }
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
                }
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