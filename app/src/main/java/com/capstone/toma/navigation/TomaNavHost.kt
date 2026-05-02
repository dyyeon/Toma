package com.capstone.toma.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.capstone.toma.VoiceRequestResult
import com.capstone.toma.TomaIntent
import com.capstone.toma.WebPageManager
import com.capstone.toma.ui.screen.*
import com.capstone.toma.viewmodel.VoiceViewModel
import com.capstone.toma.viewmodel.HomeViewModel
import com.capstone.toma.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

private val voiceSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘",
    "빠른 요리 찾아줘",
    "쉬운 요리 추천해줘"
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
                initialAiText = "사진을 분석하고 있어요. 잠시만 기다려 주세요... 📸"
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
            TomaHomeScreen(
                uiState = homeUiState,
                onSearchQueryChange = homeViewModel::updateSearchQuery,
                onSearchSubmit = {
                    val query = homeUiState.searchQuery
                    if (query.isNotBlank()) {
                        chatViewModel.resetChat()
                        chatViewModel.sendMessage(query)
                        navController.navigate(TomaDestination.Chat.route)
                        homeViewModel.updateSearchQuery("")
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
                        val isBlog = link.contains("blog.naver.com") || link.contains("tistory.com")
                        
                        if (isYoutube || isBlog) {
                            val webPageManager = WebPageManager()
                            chatViewModel.resetChat()
                            navController.navigate(TomaDestination.Chat.route)
                            
                            val displayMsg = if(isYoutube) "유튜브 분석 중: $link" else "블로그 분석 중: $link"

                            chatViewModel.startLinkAnalysis(
                                userDisplay = displayMsg,
                                initialAiText = "[1/2] 페이지 본문을 읽어오고 있어요... 📄"
                            ) { updateStatus ->
                                val (t, d, _) = webPageManager.fetchPageInfoSuspend(link)
                                if (t == null && d?.contains("실패") == true) {
                                    homeViewModel.showError(d, isDialog = true)
                                    return@startLinkAnalysis VoiceRequestResult.Error(d)
                                }
                                updateStatus("[2/2] 전문가 AI가 실전 정보를 추출 중이에요... ✨")
                                delay(600)
                                val openAi = com.capstone.toma.OpenAiManager()
                                val history = chatViewModel.uiState.value.messages.map { it.text to it.isUser }
                                openAi.processChatRequestSuspend("분석 요청: $link", history)
                            }
                            homeViewModel.updateYoutubeLink("")
                        } else {
                            homeViewModel.showError("유튜브 또는 블로그 링크만 가능합니다.")
                        }
                    }
                },
                onPhotoScanClick = { imagePickerLauncher.launch("image/*") },
                onRecentItemClick = { itemId ->
                    val item = homeUiState.recentItems.find { it.id == itemId }
                    item?.let {
                        chatViewModel.sendMessage(it.title)
                        navController.navigate(TomaDestination.Chat.route)
                    }
                },
                onRecentMoreClick = { navController.navigateSingleTop(TomaDestination.RecipeStorage.route) },
                onHomeClick = { navController.navigateSingleTop(TomaDestination.Home.route) },
                onStorageClick = { navController.navigateSingleTop(TomaDestination.RecipeStorage.route) },
                onSettingsClick = { navController.navigateSingleTop(TomaDestination.Settings.route) },
                onPrivacyPolicyClick = { navController.navigateSingleTop(TomaDestination.PrivacyPolicy.route) },
                onErrorDismiss = homeViewModel::clearError
            )
        }

        composable(TomaDestination.RecipeStorage.route) {
            RecipeStorageScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.VoiceGuide.route) {
            LaunchedEffect(Unit) {
                voiceViewModel.intentEvent.collect { intent ->
                    if (intent is TomaIntent.RECIPE_SEARCH) {
                        navController.navigate(TomaDestination.RecipeDetail.createRoute(intent.keyword)) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    }
                }
            }
            VoiceGuideScreen(
                uiState = voiceUiState,
                suggestions = voiceSuggestions,
                onMicClick = { voiceViewModel.onMicClick() },
                onSuggestionClick = { /* 필요 시 구현 */ }
            )
        }

        composable(TomaDestination.Chat.route) {
            val navEvent by chatViewModel.navigationEvent.collectAsState()
            LaunchedEffect(navEvent) {
                navEvent?.let { (keyword, recipeData) ->
                    navController.navigate(TomaDestination.RecipeDetail.createRoute(keyword, recipeData))
                    chatViewModel.clearNavigationEvent()
                }
            }
            AiChatScreen(
                uiState = chatUiState,
                onBackClick = { navController.popBackStack() },
                onInputTextChange = chatViewModel::onInputTextChange,
                onSendMessage = chatViewModel::sendMessage,
                onMicClick = { navController.navigate(TomaDestination.VoiceGuide.route) },
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

        composable(
            route = TomaDestination.RecipeDetail.route,
            arguments = listOf(
                navArgument("keyword") { type = NavType.StringType },
                navArgument("recipeData") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { backStackEntry ->
            val keyword = backStackEntry.arguments?.getString("keyword") ?: ""
            val recipeData = backStackEntry.arguments?.getString("recipeData")
            RecipeDetailScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}
