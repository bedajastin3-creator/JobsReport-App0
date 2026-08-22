package com.example.state

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.JobsReportApi
import com.example.data.Company
import com.example.data.Country
import com.example.data.JobCategory
import com.example.data.JobOpportunity
import com.example.data.LocationInfo
import com.example.data.MarketReport
import com.example.data.NotificationItem
import com.example.data.defaultCountriesList
import com.example.data.sampleCompanies
import com.example.data.sampleJobCategories
import com.example.data.sampleJobOpportunities
import com.example.data.sampleLocations
import com.example.data.sampleMarketReports
import com.example.data.sampleNotifications
import com.example.data.sampleSpotlightCompanies
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavRoute(val title: String, val routePath: String) {
    INTELLIGENCE_FEED("Intelligence Feed", "/"),
    ALL_JOBS("All Jobs List", "/jobs"),
    SAVED_JOBS("Saved Jobs", "/saved-jobs"),
    COMPANIES("Companies", "/companies"),
    REGIONS("Regions", "/regions"),
    JOB_REPORTS("All Job Reports", "/reports"),
    ADMIN_STUDIO("Admin Studio", "/admin"),
    JOB_DETAIL("Job Details", "/job-detail"),
    CATEGORY_JOBS("Category Jobs", "/category"),
    REPORT_DETAIL("Report Detail", "/report-detail"),
    REGION_DETAIL("Region Detail", "/region-detail"),
    ABOUT_US("About Us", "/about-us"),
    DISCLAIMER("Disclaimer", "/disclaimer"),
    PRIVACY_POLICY("Privacy Policy", "/privacy-policy")
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val api = JobsReportApi.create()
    private val prefs = application.getSharedPreferences("jobsreport_prefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val savedJobsType = Types.newParameterizedType(List::class.java, JobOpportunity::class.java)
    private val savedJobsAdapter = moshi.adapter<List<JobOpportunity>>(savedJobsType)

    var currentRoute by mutableStateOf(NavRoute.INTELLIGENCE_FEED)
    var selectedJob by mutableStateOf<JobOpportunity?>(null)

    // Saved / Bookmarked Jobs State (Offline memory / lightweight local storage)
    val savedJobs = mutableStateListOf<JobOpportunity>()

    val savedJobsCount: Int
        get() = savedJobs.size

    fun isJobSaved(jobId: String): Boolean {
        return savedJobs.any { it.id == jobId }
    }

    fun toggleSaveJob(job: JobOpportunity): Boolean {
        val existingIndex = savedJobs.indexOfFirst { it.id == job.id }
        val isNowSaved = if (existingIndex >= 0) {
            savedJobs.removeAt(existingIndex)
            false
        } else {
            savedJobs.add(0, job)
            true
        }
        persistSavedJobs()
        return isNowSaved
    }

    fun removeSavedJob(jobId: String) {
        savedJobs.removeAll { it.id == jobId }
        persistSavedJobs()
    }

    fun clearAllSavedJobs() {
        savedJobs.clear()
        persistSavedJobs()
    }

    private fun loadSavedJobs() {
        val json = prefs.getString("saved_jobs_json", null)
        if (!json.isNullOrBlank()) {
            try {
                val list = savedJobsAdapter.fromJson(json)
                if (!list.isNullOrEmpty()) {
                    savedJobs.clear()
                    savedJobs.addAll(list)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun persistSavedJobs() {
        try {
            val json = savedJobsAdapter.toJson(savedJobs.toList())
            prefs.edit().putString("saved_jobs_json", json).apply()
        } catch (_: Exception) {
        }
    }

    fun selectJob(job: JobOpportunity) {
        selectedJob = job
        currentRoute = NavRoute.JOB_DETAIL
    }

    // Country state
    var selectedCountry by mutableStateOf(prefs.getString("selected_country", "Worldwide") ?: "Worldwide")
    var countrySearchQuery by mutableStateOf("")
    val countries = defaultCountriesList

    val currentFlag: String
        get() {
            if (selectedCountry.lowercase() == "worldwide") return "🌍"
            return countries.find { it.name.equals(selectedCountry, ignoreCase = true) }?.flag ?: "🌍"
        }

    // Home / Market Feed Data
    var isDataLoading by mutableStateOf(false)
        private set

    var categories by mutableStateOf<List<JobCategory>>(sampleJobCategories)
        private set

    var activeJobs by mutableStateOf<List<JobOpportunity>>(sampleJobOpportunities)
        private set

    var reports by mutableStateOf<List<MarketReport>>(sampleMarketReports)
        private set

    var spotlightCompanies by mutableStateOf<List<String>>(sampleSpotlightCompanies)
        private set

    var companies by mutableStateOf<List<Company>>(sampleCompanies)
        private set

    var selectedCompany by mutableStateOf<Company?>(null)
    var selectedCompanyJobs by mutableStateOf<List<JobOpportunity>>(emptyList())
    var isCompanyJobsLoading by mutableStateOf(false)

    // Category Jobs State
    var selectedCategoryName by mutableStateOf<String?>(null)
    var categoryJobs by mutableStateOf<List<JobOpportunity>>(emptyList())
    var isCategoryJobsLoading by mutableStateOf(false)

    // Report Detail State
    var selectedReportDetail by mutableStateOf<MarketReport?>(null)
    var isReportDetailLoading by mutableStateOf(false)

    // Region / Location State
    var locations by mutableStateOf<List<LocationInfo>>(sampleLocations)
    var isLocationsLoading by mutableStateOf(false)
    var selectedRegionName by mutableStateOf<String?>(null)
    var selectedRegionLocationInfo by mutableStateOf<LocationInfo?>(null)

    fun selectRegion(regionName: String, locationInfo: LocationInfo? = null) {
        selectedRegionName = regionName
        selectedRegionLocationInfo = locationInfo ?: locations.find {
            it.name.equals(regionName, ignoreCase = true) ||
            it.region?.equals(regionName, ignoreCase = true) == true
        }
        currentRoute = NavRoute.REGION_DETAIL
    }

    fun fetchLocations() {
        viewModelScope.launch {
            isLocationsLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val res = runCatching { api.getLocations() }.getOrNull()
                    if (!res.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            locations = res
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isLocationsLoading = false
            }
        }
    }

    fun selectReport(report: MarketReport) {
        selectedReportDetail = report
        currentRoute = NavRoute.REPORT_DETAIL
        fetchReportDetail(report)
    }

    private fun fetchReportDetail(report: MarketReport) {
        val targetSlug = report.slug ?: report.id
        viewModelScope.launch {
            isReportDetailLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val detail = runCatching { api.getReportDetail(targetSlug) }.getOrNull()
                    if (detail != null) {
                        withContext(Dispatchers.Main) {
                            selectedReportDetail = detail
                        }
                    }
                }
            } catch (_: Exception) {
                // Keep initial report if network fetch fails
            } finally {
                isReportDetailLoading = false
            }
        }
    }

    fun selectCategory(categoryName: String) {
        selectedCategoryName = categoryName
        currentRoute = NavRoute.CATEGORY_JOBS
        fetchCategoryJobs(categoryName)
    }

    private fun fetchCategoryJobs(categoryName: String) {
        viewModelScope.launch {
            isCategoryJobsLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val res = runCatching { api.getCategoryJobs(categoryName) }.getOrNull()
                    val fetched = res?.jobs
                    withContext(Dispatchers.Main) {
                        if (fetched != null && fetched.isNotEmpty()) {
                            categoryJobs = fetched
                        } else {
                            // Fallback filtering from activeJobs
                            categoryJobs = activeJobs.filter { job ->
                                job.jobCategory?.contains(categoryName, ignoreCase = true) == true ||
                                job.role?.contains(categoryName, ignoreCase = true) == true ||
                                job.title.contains(categoryName, ignoreCase = true)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                categoryJobs = activeJobs.filter { job ->
                    job.jobCategory?.contains(categoryName, ignoreCase = true) == true ||
                    job.role?.contains(categoryName, ignoreCase = true) == true ||
                    job.title.contains(categoryName, ignoreCase = true)
                }
            } finally {
                isCategoryJobsLoading = false
            }
        }
    }

    fun selectCompany(company: Company?) {
        selectedCompany = company
        if (company != null) {
            fetchCompanyJobs(company)
        } else {
            selectedCompanyJobs = emptyList()
        }
    }

    private fun fetchCompanyJobs(company: Company) {
        viewModelScope.launch {
            isCompanyJobsLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val companyJobsResult = runCatching { api.getCompanyJobs(company.id) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (companyJobsResult != null && companyJobsResult.isNotEmpty()) {
                            selectedCompanyJobs = companyJobsResult
                        } else {
                            // Fallback: filter activeJobs matching company name
                            selectedCompanyJobs = activeJobs.filter {
                                it.company.contains(company.name, ignoreCase = true) || company.name.contains(it.company, ignoreCase = true)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                selectedCompanyJobs = activeJobs.filter {
                    it.company.contains(company.name, ignoreCase = true) || company.name.contains(it.company, ignoreCase = true)
                }
            } finally {
                isCompanyJobsLoading = false
            }
        }
    }

    init {
        loadSavedJobs()
        fetchDashboardData()
    }

    fun fetchDashboardData() {
        viewModelScope.launch {
            isDataLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val catResult = runCatching { api.getCategories() }.getOrNull()
                    val homeResult = runCatching { api.getHomeData(selectedCountry) }.getOrNull()
                    val marketResult = runCatching { api.getMarketData(100) }.getOrNull()
                    val compResult = runCatching { api.getCompanies() }.getOrNull()
                    val reportsResult: List<MarketReport>? = runCatching { api.getReports() }.getOrNull()

                    withContext(Dispatchers.Main) {
                        if (!catResult.isNullOrEmpty()) {
                            categories = catResult
                        }
                        if (!reportsResult.isNullOrEmpty()) {
                            reports = reportsResult
                        } else if (homeResult != null && !homeResult.reports.isNullOrEmpty()) {
                            reports = homeResult.reports
                        }
                        if (homeResult != null && !homeResult.spotlightCompanies.isNullOrEmpty()) {
                            spotlightCompanies = homeResult.spotlightCompanies
                        }
                        if (marketResult != null) {
                            val jobs = marketResult.activeJobs ?: marketResult.jobs
                            if (!jobs.isNullOrEmpty()) {
                                activeJobs = jobs
                            }
                        }
                        if (!compResult.isNullOrEmpty()) {
                            companies = compResult
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back gracefully to initialized sample data
            } finally {
                isDataLoading = false
            }
        }
    }

    fun selectCountry(countryName: String) {
        selectedCountry = countryName
        isCountryDropdownOpen = false
        prefs.edit().putString("selected_country", countryName).apply()
        fetchDashboardData()
    }

    // Admin Auth State
    var isAdmin by mutableStateOf(false)
        private set

    var passcode by mutableStateOf("")
    var loginError by mutableStateOf("")

    // Modals / Overlays
    var isMobileMenuOpen by mutableStateOf(false)
    var isLoginModalOpen by mutableStateOf(false)
    var isCountryDropdownOpen by mutableStateOf(false)
    var isNotificationBellOpen by mutableStateOf(false)
    var isSearchModalOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    // Notifications
    val notifications = mutableStateListOf<NotificationItem>().apply {
        addAll(sampleNotifications)
    }

    val unreadCount: Int
        get() = notifications.count { it.isUnread }

    fun markAllNotificationsRead() {
        for (i in notifications.indices) {
            notifications[i] = notifications[i].copy(isUnread = false)
        }
    }

    fun submitPasscode(): Boolean {
        loginError = ""
        val trimmed = passcode.trim()
        if (trimmed == "admin" || trimmed == "admin123") {
            isAdmin = true
            isLoginModalOpen = false
            passcode = ""
            return true
        } else {
            loginError = "INVALID INTEL ACCESS CODE."
            passcode = ""
            return false
        }
    }

    fun logout() {
        isAdmin = false
    }

    fun navigateTo(route: NavRoute) {
        currentRoute = route
        isMobileMenuOpen = false
    }
}

