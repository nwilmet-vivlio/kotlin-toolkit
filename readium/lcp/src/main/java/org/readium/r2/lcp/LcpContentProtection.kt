/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.lcp

import org.readium.r2.lcp.auth.LcpPassphraseAuthentication
import org.readium.r2.lcp.license.model.LicenseDocument
import org.readium.r2.shared.publication.encryption.encryption
import org.readium.r2.shared.publication.flatten
import org.readium.r2.shared.publication.protection.ContentProtection
import org.readium.r2.shared.publication.services.contentProtectionServiceFactory
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.MessageError
import org.readium.r2.shared.util.ThrowableError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.flatMap
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.mediatype.MediaTypeRetriever
import org.readium.r2.shared.util.resource.TransformingContainer

internal class LcpContentProtection(
    private val lcpService: LcpService,
    private val authentication: LcpAuthenticating,
    private val assetRetriever: AssetRetriever,
    private val mediaTypeRetriever: MediaTypeRetriever
) : ContentProtection {

    override val scheme: ContentProtection.Scheme =
        ContentProtection.Scheme.Lcp

    override suspend fun supports(
        asset: Asset
    ): Try<Boolean, ReadError> =
        Try.success(lcpService.isLcpProtected(asset))

    override suspend fun open(
        asset: Asset,
        credentials: String?,
        allowUserInteraction: Boolean
    ): Try<ContentProtection.Asset, ContentProtection.Error> {
        return when (asset) {
            is Asset.Container -> openPublication(asset, credentials, allowUserInteraction)
            is Asset.Resource -> openLicense(asset, credentials, allowUserInteraction)
        }
    }

    private suspend fun openPublication(
        asset: Asset.Container,
        credentials: String?,
        allowUserInteraction: Boolean
    ): Try<ContentProtection.Asset, ContentProtection.Error> {
        val license = retrieveLicense(asset, credentials, allowUserInteraction)
        return createResultAsset(asset, license)
    }

    private suspend fun retrieveLicense(
        asset: Asset,
        credentials: String?,
        allowUserInteraction: Boolean
    ): Try<LcpLicense, LcpException> {
        val authentication = credentials
            ?.let { LcpPassphraseAuthentication(it, fallback = this.authentication) }
            ?: this.authentication

        return lcpService.retrieveLicense(asset, authentication, allowUserInteraction)
    }

    private fun createResultAsset(
        asset: Asset.Container,
        license: Try<LcpLicense, LcpException>
    ): Try<ContentProtection.Asset, ContentProtection.Error> {
        val serviceFactory = LcpContentProtectionService
            .createFactory(license.getOrNull(), license.failureOrNull())

        val decryptor = LcpDecryptor(license.getOrNull(), mediaTypeRetriever)

        val container = TransformingContainer(asset.container, decryptor::transform)

        val protectedFile = ContentProtection.Asset(
            mediaType = asset.mediaType,
            container = container,
            onCreatePublication = {
                decryptor.encryptionData = (manifest.readingOrder + manifest.resources + manifest.links)
                    .flatten()
                    .mapNotNull {
                        it.properties.encryption?.let { enc -> it.url() to enc }
                    }
                    .toMap()

                servicesBuilder.contentProtectionServiceFactory = serviceFactory
            }
        )

        return Try.success(protectedFile)
    }

    private suspend fun openLicense(
        licenseAsset: Asset.Resource,
        credentials: String?,
        allowUserInteraction: Boolean
    ): Try<ContentProtection.Asset, ContentProtection.Error> {
        val license = retrieveLicense(licenseAsset, credentials, allowUserInteraction)

        val licenseDoc = license.getOrNull()?.license
            ?: licenseAsset.resource.read()
                .map {
                    try {
                        LicenseDocument(it)
                    } catch (e: Exception) {
                        return Try.failure(
                            ContentProtection.Error.AccessError(
                                ReadError.Content(
                                    MessageError(
                                        "Failed to read the LCP license document",
                                        cause = ThrowableError(e)
                                    )
                                )
                            )
                        )
                    }
                }
                .getOrElse {
                    return Try.failure(
                        ContentProtection.Error.AccessError(it)
                    )
                }

        val link = licenseDoc.publicationLink
        val url = (link.url() as? AbsoluteUrl)
            ?: return Try.failure(
                ContentProtection.Error.AccessError(
                    ReadError.Content(
                        MessageError(
                            "The LCP license document does not contain a valid link to the publication"
                        )
                    )
                )
            )

        val asset =
            if (link.mediaType != null) {
                assetRetriever.retrieve(
                    url,
                    mediaType = link.mediaType,
                    containerType = if (link.mediaType.isZip) MediaType.ZIP else null
                )
                    .map { it as Asset.Container }
                    .mapFailure { it.wrap() }
            } else {
                assetRetriever.retrieve(url)
                    .mapFailure { it.wrap() }
                    .flatMap {
                        if (it is Asset.Container) {
                            Try.success((it))
                        } else {
                            Try.failure(
                                ContentProtection.Error.UnsupportedAsset(
                                    MessageError(
                                        "LCP license points to an unsupported publication."
                                    )
                                )
                            )
                        }
                    }
            }

        return asset.flatMap { createResultAsset(it, license) }
    }

    private fun AssetRetriever.Error.wrap(): ContentProtection.Error =
        when (this) {
            is AssetRetriever.Error.ArchiveFormatNotSupported ->
                ContentProtection.Error.UnsupportedAsset(this)
            is AssetRetriever.Error.AccessError ->
                ContentProtection.Error.AccessError(cause)
            is AssetRetriever.Error.SchemeNotSupported ->
                ContentProtection.Error.UnsupportedAsset(this)
        }
}
