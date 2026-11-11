package com.printbook.app.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object LibraryRoute
@Serializable data object CaptureRoute
@Serializable data object SettingsRoute

@Serializable data class BookDetailRoute(val bookId: Long)
@Serializable data class ReaderRoute(val bookId: Long, val pageIndex: Int = -1)
@Serializable data class EditorRoute(val bookId: Long)
