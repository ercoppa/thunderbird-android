package com.fsck.k9.activity

import com.fsck.k9.activity.compose.MessageDatabaseRecipientProvider
import org.koin.dsl.module

val activityModule = module {
    single {
        MessageLoaderHelperFactory(
            messageViewInfoExtractorFactory = get(),
            messageReaderHtmlSettingsProvider = get(),
            messageComposerHtmlSettingsProvider = get(),
        )
    }
    factory { MessageDatabaseRecipientProvider() }
}
