package com.capstone.toma.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capstone.toma.ui.screen.AiChatScreen
import com.capstone.toma.ui.screen.ContactUsScreen
import com.capstone.toma.ui.screen.CustomerCenterScreen
import com.capstone.toma.ui.screen.EmailSettingScreen
import com.capstone.toma.ui.screen.PrivacyPolicyScreen
import com.capstone.toma.ui.screen.PushSettingScreen
import com.capstone.toma.ui.screen.RecentHistoryScreen
import com.capstone.toma.ui.screen.RecipeConfirmScreen
import com.capstone.toma.ui.screen.RecipeDetailScreen
import com.capstone.toma.ui.screen.RecipeStorageScreen
import com.capstone.toma.ui.screen.SettingsScreen
import com.capstone.toma.ui.screen.TomaHomeScreen
import com.capstone.toma.ui.screen.VoiceGuideScreen
import com.capstone.toma.viewmodel.ChatNavigationEvent
import com.capstone.toma.viewmodel.ChatViewModel
import com.capstone.toma.viewmodel.HomeViewModel
import com.capstone.toma.viewmodel.VoiceViewModel

private val voiceSuggestions = listOf(
    "메뉴 추천해줘",
    "재료로 요리 찾아줘",
    "간단한 레시피 알려줘"
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
    val voiceResult by voiceViewModel.searchResult.collectAsState()

    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = TomaDestination.Home.route
    ) {
        composable(TomaDestination.Home.route) {
            LaunchedEffect(Unit) {
                homeViewModel.refreshRecentItems()
            }

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
                    homeViewModel.showError("링크 분석 기능은 아직 다시 연결하지 않았습니다.", isDialog = true)
                },
                onPhotoScanClick = {
                    homeViewModel.showError("이미지 분석 기능은 아직 다시 연결하지 않았습니다.", isDialog = true)
                },
                onRecentItemClick = { itemId ->
                    val item = homeUiState.recentItems.find { it.id == itemId }
                    item?.let {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(it.title, it.recipeDataJson)
                        )
                    }
                },
                onRecentMoreClick = {
                    navController.navigateSingleTop(TomaDestination.RecentHistory.route)
                },
                onHomeClick = {
                    navController.navigateSingleTop(TomaDestination.Home.route)
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
            RecipeStorageScreen(
                onBackClick = { navController.popBackStack() }
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
                        TomaDestination.RecipeDetail.createRoute(item.title, item.recipeDataJson)
                    )
                }
            )
        }

        composable(TomaDestination.VoiceGuide.route) {
            LaunchedEffect(voiceResult) {
                voiceResult?.let { result ->
                    if (result.requestType == "recipe_search") {
                        navController.navigate(TomaDestination.RecipeConfirm.createRoute(result.keyword, null)) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    } else {
                        chatViewModel.addInitialMessages(result.userQuery, result.responseMessage)
                        navController.navigate(TomaDestination.Chat.route) {
                            popUpTo(TomaDestination.VoiceGuide.route) { inclusive = true }
                        }
                    }
                    voiceViewModel.reset()
                }
            }

            VoiceGuideScreen(
                uiState = voiceUiState,
                suggestions = voiceSuggestions,
                onMicClick = { voiceViewModel.onMicClick() },
                onSuggestionClick = { text -> voiceViewModel.onSuggestionClick(text) }
            )
        }

        composable(TomaDestination.Chat.route) {
            val navEvent by chatViewModel.navigationEvent.collectAsState()
            val errorEvent by chatViewModel.errorEvent.collectAsState()

            LaunchedEffect(navEvent) {
                when (val event = navEvent) {
                    is ChatNavigationEvent.ToConfirm -> {
                        navController.navigate(
                            TomaDestination.RecipeConfirm.createRoute(
                                event.keyword,
                                event.recipeData
                            )
                        )
                        chatViewModel.clearNavigationEvent()
                    }
                    is ChatNavigationEvent.ToDetail -> {
                        navController.navigate(
                            TomaDestination.RecipeDetail.createRoute(
                                event.keyword,
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
                onSendMessage = chatViewModel::sendMessage,
                onMicClick = {
                    navController.navigate(TomaDestination.VoiceGuide.route)
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

        composable(TomaDestination.PushSetting.route) {
            PushSettingScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.EmailSetting.route) {
            EmailSettingScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.CustomerCenter.route) {
            CustomerCenterScreen(onBackClick = { navController.popBackStack() })
        }

        composable(TomaDestination.ContactUs.route) {
            ContactUsScreen(onBackClick = { navController.popBackStack() })
        }

        composable(
            route = TomaDestination.RecipeConfirm.route,
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
            RecipeConfirmScreen(
                keyword = keyword,
                recipeDataJson = recipeData,
                onBackClick = { navController.popBackStack() },
                onConfirmClick = {
                    homeViewModel.saveRecentRecipe(
                        keyword = keyword,
                        recipeDataJson = recipeData
                    )
                    navController.navigate(TomaDestination.RecipeDetail.createRoute(keyword, recipeData)) {
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
                navArgument("recipeData") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
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

        composable(TomaDestination.PrivacyPolicy.route) {
            PrivacyPolicyScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        launchSingleTop = true
    }
}
