/*
 * Copyright (C) 2017-2018 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner

import ch.frankel.slf4k.*

import com.here.ort.downloader.Downloader
import com.here.ort.model.Environment
import com.here.ort.model.OrtResult
import com.here.ort.model.OutputFormat
import com.here.ort.model.Package
import com.here.ort.model.ProjectScanScopes
import com.here.ort.model.ScanRecord
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScannerRun
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.readValue
import com.here.ort.scanner.scanners.*
import com.here.ort.utils.log

import java.io.File

const val TOOL_NAME = "scanner"
const val HTTP_CACHE_PATH = "$TOOL_NAME/cache/http"

/**
 * The class to run license / copyright scanners. The signatures of public functions in this class define the library API.
 */
abstract class Scanner {
    companion object {
        /**
         * The list of all available scanners. This needs to be initialized lazily to ensure the referred objects,
         * which derive from this class, exist.
         */
        val ALL by lazy {
            listOf(
                    Askalono,
                    BoyterLc,
                    FileCounter,
                    Licensee,
                    ScanCode
            )
        }
    }

    /**
     * Scan the [packages] using this [Scanner].
     *
     * @param packages The packages to scan.
     * @param outputDirectory Where to store the scan results.
     * @param downloadDirectory Where to store the downloaded source code. If null the source code is downloaded to the
     *                          outputDirectory.
     *
     * @return The scan results by identifier. It can contain multiple results for one identifier if the
     *         cache contains more than one result for the specification of this scanner.
     */
    abstract fun scan(packages: List<Package>, outputDirectory: File, downloadDirectory: File? = null)
            : Map<Package, List<ScanResult>>

    /**
     * Return the Java class name as a simple way to refer to the scanner.
     */
    override fun toString(): String = javaClass.simpleName

    fun scanDependenciesFile(dependenciesFile: File, scopesToScan: List<String>, downloadDir: File?, outputDir: File,
                             outputFormats: List<OutputFormat>, config: ScannerConfiguration): OrtResult {
        require(dependenciesFile.isFile) {
            "Provided path for the configuration does not refer to a file: ${dependenciesFile.absolutePath}"
        }

        val ortResult = dependenciesFile.readValue(OrtResult::class.java)

        require(ortResult.analyzer != null) {
            "The provided dependencies file '${dependenciesFile.invariantSeparatorsPath}' does not contain an " +
                    "analyzer result."
        }

        val analyzerResult = ortResult.analyzer!!.result

        // Add the projects as packages to scan.
        val consolidatedProjectPackageMap = Downloader().consolidateProjectPackagesByVcs(analyzerResult.projects)
        val consolidatedReferencePackages = consolidatedProjectPackageMap.keys.map { it.toCuratedPackage() }

        val projectScanScopes = if (scopesToScan.isNotEmpty()) {
            println("Limiting scan to scopes: $scopesToScan")

            analyzerResult.projects.map { project ->
                project.scopes.map { it.name }.partition { it in scopesToScan }.let {
                    ProjectScanScopes(project.id, it.first.toSortedSet(), it.second.toSortedSet())
                }
            }
        } else {
            analyzerResult.projects.map {
                val scopes = it.scopes.map { it.name }
                ProjectScanScopes(it.id, scopes.toSortedSet(), sortedSetOf())
            }
        }.toSortedSet()

        val packagesToScan = if (scopesToScan.isNotEmpty()) {
            consolidatedReferencePackages + analyzerResult.packages.filter { pkg ->
                analyzerResult.projects.any { it.scopes.any { it.name in scopesToScan && pkg.pkg in it } }
            }
        } else {
            consolidatedReferencePackages + analyzerResult.packages
        }.toSortedSet()

        val results = scan(packagesToScan.map { it.pkg }, outputDir, downloadDir)
        results.forEach { pkg, result ->
            outputFormats.forEach { format ->
                File(outputDir, "scanResults/${pkg.id.toPath()}/scan-results.${format.fileExtension}").also {
                    format.mapper.writeValue(it, ScanResultContainer(pkg.id, result))
                }
            }

            log.debug { "Declared licenses for '${pkg.id}': ${pkg.declaredLicenses.joinToString()}" }
            log.debug { "Detected licenses for '${pkg.id}': ${result.flatMap { it.summary.licenses }.joinToString()}" }
        }

        val resultContainers = results.map { (pkg, results) ->
            // Remove the raw results from the scan results to reduce the size of the scan result.
            // TODO: Consider adding an option to keep the raw results.
            ScanResultContainer(pkg.id, results.map { it.copy(rawResult = null) })
        }.toSortedSet()

        // Add scan results from de-duplicated project packages to result.
        consolidatedProjectPackageMap.forEach { referencePackage, deduplicatedPackages ->
            resultContainers.find { it.id == referencePackage.id }?.let { resultContainer ->
                deduplicatedPackages.forEach {
                    resultContainers += resultContainer.copy(id = it.id)
                }
            }
        }

        val scanRecord = ScanRecord(projectScanScopes, resultContainers, ScanResultsCache.stats)

        val scannerRun = ScannerRun(Environment(), config, scanRecord)

        return OrtResult(ortResult.repository, ortResult.analyzer, scannerRun)
    }
}
