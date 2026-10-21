package com.example.state

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.example.data.CreateStoryPayload
import com.example.data.StoriesResponse
import com.example.data.StoryType
import com.example.data.StoryUser
import com.example.data.UploadResponse
import com.example.data.getDefaultStoryAvatar
import com.example.data.isStoryExpired
import com.example.data.isUserVerified
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray

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
    PRIVACY_POLICY("Privacy Policy", "/privacy-policy"),
    STORIES("Job Stories", "/stories"),
    USER_AUTH("Account / Sign In", "/auth"),
    USER_PROFILE("User Profile", "/profile"),
    USER_JOB_INPUT("Post a Job", "/post-job")
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val api = JobsReportApi.create()
    private val prefs = application.getSharedPreferences("jobsreport_prefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val savedJobsType = Types.newParameterizedType(List::class.java, JobOpportunity::class.java)
    private val savedJobsAdapter = moshi.adapter<List<JobOpportunity>>(savedJobsType)

    private val storyListType = Types.newParameterizedType(List::class.java, StoryType::class.java)
    private val storyListAdapter = moshi.adapter<List<StoryType>>(storyListType)
    private val storiesResponseAdapter = moshi.adapter(StoriesResponse::class.java)
    private val uploadResponseAdapter = moshi.adapter(UploadResponse::class.java)

    var currentRoute by mutableStateOf(NavRoute.INTELLIGENCE_FEED)
    var selectedJob by mutableStateOf<JobOpportunity?>(null)

    // Story & Community Intel State (exact parity with Story.tsx)
    var stories by mutableStateOf<List<StoryType>>(emptyList())
        private set
    var isStoriesLoading by mutableStateOf(false)
        private set
    var viewingStory by mutableStateOf<StoryType?>(null)
    var isCreateStoryOpen by mutableStateOf(false)
    var isUserProfileModalOpen by mutableStateOf(false)
    var isUserAuthModalOpen by mutableStateOf(false)

    // User Session State (exact sync with jobsreport_token)
    var userToken by mutableStateOf<String?>(null)
        private set
    var currentUser by mutableStateOf<StoryUser?>(null)
        private set
    var viewingProfileUser by mutableStateOf<StoryUser?>(null)
    val followedUserIds = mutableStateListOf<String>()

    // Real server-verified user status & profile cache
    val verifiedUsersMap = mutableStateMapOf<String, Boolean>()
    val userProfilesCache = mutableStateMapOf<String, StoryUser>()

    private val pendingProfileFetches = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun isUserVerified(userId: String, fallbackUser: StoryUser? = null): Boolean {
        if (userId.isBlank()) return false
        verifiedUsersMap[userId]?.let { return it }
        val cached = userProfilesCache[userId]
        if (cached != null) {
            val v = cached.isUserVerified
            verifiedUsersMap[userId] = v
            return v
        }
        if (fallbackUser != null && fallbackUser.isVerified != null) {
            val v = fallbackUser.isUserVerified
            verifiedUsersMap[userId] = v
            return v
        }
        // Fetch live from server just as UserProfile does
        ensureUserProfileLoaded(userId)
        return false
    }

    fun getResolvedUser(userId: String, fallbackUser: StoryUser? = null): StoryUser {
        if (userId.isBlank()) {
            return fallbackUser ?: StoryUser(
                id = "",
                name = "User",
                profileImageUrl = getDefaultStoryAvatar("User", "anon")
            )
        }
        val cached = userProfilesCache[userId]
        if (cached != null) return cached
        ensureUserProfileLoaded(userId)
        return fallbackUser ?: StoryUser(
            id = userId,
            name = "User",
            profileImageUrl = getDefaultStoryAvatar("User", userId)
        )
    }

    fun ensureUserProfileLoaded(userId: String) {
        if (userId.isBlank() || userProfilesCache.containsKey(userId) || pendingProfileFetches.contains(userId)) return
        pendingProfileFetches.add(userId)
        viewModelScope.launch {
            try {
                val user = fetchUserProfile(userId)
                if (user != null) {
                    withContext(Dispatchers.Main) {
                        userProfilesCache[userId] = user
                        verifiedUsersMap[userId] = user.isUserVerified
                    }
                }
            } finally {
                pendingProfileFetches.remove(userId)
            }
        }
    }

    fun isUserFollowed(userId: String): Boolean = followedUserIds.contains(userId)

    fun toggleFollowUser(userId: String): Boolean {
        return if (followedUserIds.contains(userId)) {
            followedUserIds.remove(userId)
            false
        } else {
            followedUserIds.add(userId)
            true
        }
    }

    fun openUserProfile(user: StoryUser? = null) {
        viewingProfileUser = user
        isMobileMenuOpen = false
        currentRoute = NavRoute.USER_PROFILE
    }

    fun openUserProfileById(userId: String) {
        val foundUser = stories.firstOrNull { it.userId == userId }?.user
            ?: if (currentUser?.id == userId) currentUser
            else StoryUser(
                id = userId,
                name = "User",
                username = "user_${userId.takeLast(4)}",
                profileImageUrl = getDefaultStoryAvatar("User", userId)
            )
        openUserProfile(foundUser)
    }

    var editingJob by mutableStateOf<JobOpportunity?>(null)
    var jobInputReturnRoute by mutableStateOf<NavRoute?>(null)

    fun openPostJob(returnRoute: NavRoute? = currentRoute) {
        if (userToken == null) {
            openAuth()
            return
        }
        editingJob = null
        jobInputReturnRoute = returnRoute
        isMobileMenuOpen = false
        currentRoute = NavRoute.USER_JOB_INPUT
    }

    fun openEditJob(job: JobOpportunity, returnRoute: NavRoute? = currentRoute) {
        if (userToken == null) {
            openAuth()
            return
        }
        editingJob = job
        jobInputReturnRoute = returnRoute
        isMobileMenuOpen = false
        currentRoute = NavRoute.USER_JOB_INPUT
    }

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
        loadCurrentUser()
        fetchDashboardData()
        loadStories()
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

    fun navigateTo(route: NavRoute) {
        currentRoute = route
        isMobileMenuOpen = false
    }

    // ============================================================
    // USER AUTH & SESSION (jobsreport_token sync)
    // ============================================================
    private fun loadCurrentUser() {
        userToken = prefs.getString("jobsreport_token", null)
        val savedId = prefs.getString("user_id", null)
        val savedName = prefs.getString("user_name", null)
        val savedUsername = prefs.getString("user_username", null)
        val savedAvatar = prefs.getString("user_avatar", null)
        val savedEmail = prefs.getString("user_email", null)
        val savedHeadline = prefs.getString("user_headline", null)
        val savedLocation = prefs.getString("user_location", null)
        val savedBio = prefs.getString("user_bio", null)
        val savedWebsite = prefs.getString("user_website", null)
        val savedWork = prefs.getString("user_work", null)
        val savedEducation = prefs.getString("user_education", null)
        val savedCover = prefs.getString("user_cover", null)
        val isVerifiedInt = prefs.getInt("user_is_verified", 0)

        if (!userToken.isNullOrBlank() && !savedId.isNullOrBlank() && !savedName.isNullOrBlank()) {
            currentUser = StoryUser(
                id = savedId,
                name = savedName,
                username = savedUsername,
                email = savedEmail,
                profileImageUrl = savedAvatar,
                coverImageUrl = savedCover,
                headline = savedHeadline,
                location = savedLocation,
                bio = savedBio,
                work = savedWork,
                education = savedEducation,
                website = savedWebsite,
                isVerified = isVerifiedInt,
                followersCount = prefs.getInt("user_followers", 0),
                followingCount = prefs.getInt("user_following", 0),
                jobsCount = prefs.getInt("user_jobs_count", 0),
                storiesCount = prefs.getInt("user_stories_count", 0),
                jobAdvertsCount = prefs.getInt("user_job_adverts", 0),
                joinedDate = prefs.getString("user_joined_date", null),
                skills = emptyList()
            )
            // Proactively refresh own user profile from live API
            viewModelScope.launch {
                val liveUser = fetchUserProfile(savedId)
                if (liveUser != null) {
                    withContext(Dispatchers.Main) {
                        currentUser = liveUser
                    }
                }
            }
        } else {
            currentUser = null
        }
    }

    fun updateCurrentUser(
        name: String,
        username: String? = null,
        email: String? = null,
        token: String? = null,
        headline: String? = null,
        location: String? = null,
        bio: String? = null,
        website: String? = null,
        avatarUrl: String? = null,
        coverUrl: String? = null,
        work: String? = null,
        education: String? = null,
        isVerified: Any? = null,
        followersCount: Int? = null,
        followingCount: Int? = null,
        jobsCount: Int? = null,
        storiesCount: Int? = null,
        jobAdvertsCount: Int? = null,
        joinedDate: String? = null
    ) {
        val current = currentUser
        val id = current?.id ?: ("usr_" + java.util.UUID.randomUUID().toString().take(8))
        val resolvedAvatar = avatarUrl?.ifBlank { null }
            ?: current?.profileImageUrl
            ?: getDefaultStoryAvatar(name, id)
        val resolvedCover = coverUrl?.ifBlank { null } ?: current?.coverImageUrl
        val resolvedHeadline = headline?.ifBlank { null } ?: current?.headline
        val resolvedLocation = location?.ifBlank { null } ?: current?.location
        val resolvedBio = bio?.ifBlank { null } ?: current?.bio
        val resolvedWebsite = website?.ifBlank { null } ?: current?.website
        val resolvedWork = work?.ifBlank { null } ?: current?.work
        val resolvedEducation = education?.ifBlank { null } ?: current?.education
        val resolvedVerified = isVerified ?: current?.isVerified

        val updated = StoryUser(
            id = id,
            name = name.ifBlank { "User" },
            username = username?.ifBlank { null } ?: current?.username,
            email = email?.ifBlank { null } ?: current?.email,
            profileImageUrl = resolvedAvatar,
            coverImageUrl = resolvedCover,
            headline = resolvedHeadline,
            location = resolvedLocation,
            bio = resolvedBio,
            work = resolvedWork,
            education = resolvedEducation,
            website = resolvedWebsite,
            isVerified = resolvedVerified,
            followersCount = followersCount ?: current?.followersCount ?: 0,
            followingCount = followingCount ?: current?.followingCount ?: 0,
            jobsCount = jobsCount ?: current?.jobsCount ?: 0,
            storiesCount = storiesCount ?: current?.storiesCount ?: 0,
            jobAdvertsCount = jobAdvertsCount ?: current?.jobAdvertsCount ?: 0,
            joinedDate = joinedDate ?: current?.joinedDate,
            skills = current?.skills ?: emptyList()
        )
        currentUser = updated
        if (!token.isNullOrBlank()) {
            userToken = token.trim()
            prefs.edit().putString("jobsreport_token", token.trim()).apply()
        }
        val isVerifiedInt = if (updated.isUserVerified) 1 else 0
        prefs.edit()
            .putString("user_id", id)
            .putString("user_name", updated.name)
            .putString("user_username", updated.username)
            .putString("user_avatar", resolvedAvatar)
            .putString("user_cover", resolvedCover)
            .putString("user_email", updated.email)
            .putString("user_headline", resolvedHeadline)
            .putString("user_location", resolvedLocation)
            .putString("user_bio", resolvedBio)
            .putString("user_work", resolvedWork)
            .putString("user_education", resolvedEducation)
            .putString("user_website", resolvedWebsite)
            .putInt("user_is_verified", isVerifiedInt)
            .putInt("user_followers", updated.followersCount ?: 0)
            .putInt("user_following", updated.followingCount ?: 0)
            .putInt("user_jobs_count", updated.jobsCount ?: 0)
            .putInt("user_stories_count", updated.storiesCount ?: 0)
            .putInt("user_job_adverts", updated.jobAdvertsCount ?: 0)
            .apply()
    }

    suspend fun fetchUserProfile(userId: String): StoryUser? = withContext(Dispatchers.IO) {
        try {
            val res = runCatching { api.getUserProfile(userId) }.getOrNull()
            if (res != null) {
                val jsonStr = res.string().trim()
                val json = JSONObject(jsonStr)
                val userObj = json.optJSONObject("user")
                    ?: if (json.has("id") || json.has("name") || json.has("is_verified") || json.has("username")) json else null
                if (userObj != null) {
                    val fetchedUser = StoryUser(
                        id = userObj.optString("id", userId),
                        name = userObj.optString("name", "User"),
                        username = userObj.optString("username", null),
                        email = userObj.optString("email", null),
                        profileImageUrl = userObj.optString("profile_image_url", userObj.optString("avatar", null)),
                        coverImageUrl = userObj.optString("cover_image_url", null),
                        headline = userObj.optString("headline", null),
                        bio = userObj.optString("bio", null),
                        work = userObj.optString("work", null),
                        education = userObj.optString("education", null),
                        location = userObj.optString("location", null),
                        website = userObj.optString("website", null),
                        country = userObj.optString("country", null),
                        role = userObj.optString("role", null),
                        isVerified = userObj.opt("is_verified") ?: userObj.opt("isVerified"),
                        followersCount = userObj.optInt("followers_count", 0),
                        followingCount = userObj.optInt("following_count", 0),
                        jobsCount = userObj.optInt("jobs_count", 0),
                        storiesCount = userObj.optInt("stories_count", 0),
                        isFollowing = userObj.optBoolean("is_following", false),
                        createdAt = userObj.optString("created_at", null)
                    )
                    withContext(Dispatchers.Main) {
                        userProfilesCache[userId] = fetchedUser
                        verifiedUsersMap[userId] = fetchedUser.isUserVerified
                    }
                    return@withContext fetchedUser
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchUserJobs(userId: String): List<JobOpportunity> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext emptyList()
        try {
            val res = runCatching { api.getUserJobs(userId, limit = 100) }.getOrNull()
            if (res != null) {
                val rawStr = res.string().trim()
                val list = mutableListOf<JobOpportunity>()
                val arr = if (rawStr.startsWith("[")) {
                    JSONArray(rawStr)
                } else if (rawStr.startsWith("{")) {
                    val json = JSONObject(rawStr)
                    json.optJSONArray("jobs")
                        ?: json.optJSONArray("data")
                        ?: json.optJSONArray("userJobs")
                } else null

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            JobOpportunity(
                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                title = obj.optString("title", "Job Title"),
                                company = obj.optString("company", obj.optString("company_name", "Company")),
                                location = obj.optString("location", "Remote"),
                                salary = obj.optString("salary", null),
                                role = obj.optString("role", obj.optString("job_category", "Technology & Engineering")),
                                logoUrl = obj.optString("logoUrl", obj.optString("company_logo", null)),
                                active = if (obj.has("active")) obj.optBoolean("active", true) else (obj.optInt("is_active", 1) == 1),
                                postedAt = obj.optString("postedAt", obj.optString("posted_at", "Recent")),
                                expiresAt = obj.optString("expiresAt", obj.optString("expires_at", null)),
                                description = obj.optString("description", null),
                                companyWebsite = obj.optString("companyWebsite", obj.optString("company_website", null)),
                                whatsappNumber = obj.optString("whatsapp_number", obj.optString("whatsappNumber", null)),
                                applicationInstructions = obj.optString("application_instructions", obj.optString("applicationInstructions", null)),
                                url = obj.optString("url", null),
                                employmentType = obj.optString("employment_type", obj.optString("employmentType", "FULL_TIME")),
                                workplaceType = obj.optString("workplace_type", obj.optString("workplaceType", "Onsite")),
                                educationLevel = obj.optString("education_level", obj.optString("educationLevel", "Degree / Diploma")),
                                experienceMonths = obj.optInt("experience_months", obj.optInt("experienceMonths", 24)),
                                city = obj.optString("city", "Dar es Salaam"),
                                region = obj.optString("region", "Dar es Salaam"),
                                country = obj.optString("country", "Tanzania"),
                                jobCategory = obj.optString("job_category", obj.optString("jobCategory", "Technology & Engineering"))
                            )
                        )
                    }
                }
                return@withContext list
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun followUserApi(userId: String, shouldFollow: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val auth = userToken?.let { "Bearer $it" }
            if (shouldFollow) {
                runCatching { api.followUser(auth, userId) }
            } else {
                runCatching { api.unfollowUser(auth, userId) }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    suspend fun saveProfileApi(
        name: String,
        headline: String?,
        bio: String?,
        work: String?,
        education: String?,
        location: String?,
        website: String?,
        avatarUrl: String?,
        coverUrl: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val auth = userToken?.let { "Bearer $it" }
            val json = JSONObject().apply {
                put("name", name)
                put("headline", headline ?: JSONObject.NULL)
                put("bio", bio ?: JSONObject.NULL)
                put("work", work ?: JSONObject.NULL)
                put("education", education ?: JSONObject.NULL)
                put("location", location ?: JSONObject.NULL)
                put("website", website ?: JSONObject.NULL)
                put("profile_image_url", avatarUrl ?: JSONObject.NULL)
                put("cover_image_url", coverUrl ?: JSONObject.NULL)
            }
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            runCatching { api.updateProfile(auth, body) }
            withContext(Dispatchers.Main) {
                updateCurrentUser(
                    name = name,
                    headline = headline,
                    location = location,
                    bio = bio,
                    website = website,
                    avatarUrl = avatarUrl,
                    coverUrl = coverUrl,
                    work = work,
                    education = education
                )
            }
            true
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                updateCurrentUser(
                    name = name,
                    headline = headline,
                    location = location,
                    bio = bio,
                    website = website,
                    avatarUrl = avatarUrl,
                    coverUrl = coverUrl,
                    work = work,
                    education = education
                )
            }
            true
        }
    }

    suspend fun deleteUserJob(jobId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val auth = userToken?.let { "Bearer $it" }
            runCatching { api.deleteJob(auth, jobId) }
            activeJobs = activeJobs.filter { it.id != jobId }
            true
        } catch (_: Exception) {
            activeJobs = activeJobs.filter { it.id != jobId }
            true
        }
    }

    fun openAuth() {
        isUserAuthModalOpen = true
    }

    fun closeAuth() {
        isUserAuthModalOpen = false
    }

    fun logout() {
        isAdmin = false
        userToken = null
        val randomId = "usr_" + java.util.UUID.randomUUID().toString().take(8)
        val defaultName = "Job Seeker"
        val avatar = getDefaultStoryAvatar(defaultName, randomId)
        currentUser = StoryUser(
            id = randomId,
            name = defaultName,
            username = "seeker_${randomId.takeLast(4)}",
            email = null,
            profileImageUrl = avatar,
            headline = "Job Seeker & Professional",
            location = "Global",
            bio = "",
            website = ""
        )
        prefs.edit()
            .remove("jobsreport_token")
            .putString("user_id", randomId)
            .putString("user_name", defaultName)
            .putString("user_username", currentUser?.username)
            .putString("user_avatar", avatar)
            .remove("user_email")
            .remove("user_headline")
            .remove("user_location")
            .remove("user_bio")
            .remove("user_website")
            .apply()
        if (currentRoute == NavRoute.USER_PROFILE) {
            currentRoute = NavRoute.INTELLIGENCE_FEED
        }
    }

    suspend fun login(email: String, password: String, remember: Boolean = true): com.example.data.AuthResult = withContext(Dispatchers.IO) {
        try {
            val payload = com.example.data.LoginPayload(email = email.trim(), password = password, remember = remember)
            val responseBody = runCatching { api.login(payload) }.getOrNull()
            if (responseBody != null) {
                val jsonStr = responseBody.string()
                val json = org.json.JSONObject(jsonStr)
                val token = json.optString("token", json.optString("accessToken", ""))
                if (token.isNotBlank()) {
                    val userObj = json.optJSONObject("user")
                    val name = userObj?.optString("name") ?: email.substringBefore("@").replace(".", " ").replaceFirstChar { it.uppercase() }
                    val username = userObj?.optString("username") ?: email.substringBefore("@")
                    val avatar = userObj?.optString("profileImageUrl", userObj?.optString("avatar", null))
                        ?: getDefaultStoryAvatar(name, username)
                    val userId = userObj?.optString("id", "").orEmpty()
                    val isVerifiedVal = userObj?.opt("is_verified") ?: userObj?.opt("isVerified")

                    withContext(Dispatchers.Main) {
                        updateCurrentUser(
                            name = name,
                            username = username,
                            email = email,
                            token = token,
                            avatarUrl = avatar,
                            isVerified = isVerifiedVal
                        )
                        if (userId.isNotBlank()) {
                            viewModelScope.launch {
                                fetchUserProfile(userId)
                            }
                        }
                    }
                    return@withContext com.example.data.AuthResult(success = true, token = token, message = "Welcome back, $name!")
                } else {
                    val errMsg = json.optString("message", json.optString("error", "Invalid credentials. Please check your email and password."))
                    return@withContext com.example.data.AuthResult(success = false, message = errMsg)
                }
            } else {
                return@withContext com.example.data.AuthResult(success = false, message = "Invalid email or password. Please try again.")
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Authentication failed. Please check your network connection and credentials."
            return@withContext com.example.data.AuthResult(success = false, message = msg)
        }
    }

    suspend fun register(
        name: String,
        email: String,
        password: String,
        username: String? = null,
        headline: String? = null,
        location: String? = null
    ): com.example.data.AuthResult = withContext(Dispatchers.IO) {
        try {
            val payload = com.example.data.RegisterPayload(
                name = name.trim(),
                email = email.trim(),
                password = password,
                username = username?.trim(),
                headline = headline?.trim(),
                location = location?.trim()
            )
            val responseBody = runCatching { api.register(payload) }.getOrNull()
            if (responseBody != null) {
                val jsonStr = responseBody.string()
                val json = org.json.JSONObject(jsonStr)
                val token = json.optString("token", json.optString("accessToken", ""))
                val userObj = json.optJSONObject("user")
                val resolvedName = userObj?.optString("name", name.trim()) ?: name.trim()
                val resolvedUsername = userObj?.optString("username", username?.trim() ?: email.substringBefore("@"))
                    ?: (username?.trim() ?: email.substringBefore("@"))
                val avatar = userObj?.optString("profileImageUrl", userObj?.optString("avatar", null))
                    ?: getDefaultStoryAvatar(resolvedName, resolvedUsername)
                val userId = userObj?.optString("id", "").orEmpty()
                val isVerifiedVal = userObj?.opt("is_verified") ?: userObj?.opt("isVerified")

                if (token.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        updateCurrentUser(
                            name = resolvedName,
                            username = resolvedUsername,
                            email = email.trim(),
                            token = token,
                            avatarUrl = avatar,
                            headline = headline?.trim(),
                            location = location?.trim(),
                            isVerified = isVerifiedVal
                        )
                        if (userId.isNotBlank()) {
                            viewModelScope.launch {
                                fetchUserProfile(userId)
                            }
                        }
                    }
                    return@withContext com.example.data.AuthResult(success = true, token = token, message = "Account created successfully!")
                } else {
                    val errMsg = json.optString("message", json.optString("error", "Registration could not be completed."))
                    return@withContext com.example.data.AuthResult(success = json.optBoolean("success", false), message = errMsg)
                }
            } else {
                return@withContext com.example.data.AuthResult(success = false, message = "Registration failed. Please try a different email or try again later.")
            }
        } catch (e: Exception) {
            return@withContext com.example.data.AuthResult(success = false, message = e.message ?: "Registration error.")
        }
    }

    suspend fun forgotPassword(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = com.example.data.ForgotPasswordPayload(email = email.trim())
            runCatching { api.forgotPassword(payload) }
            Unit
        }
    }

    // ============================================================
    // STORY SERVICE METHODS (Exact endpoints & logic as Story.tsx)
    // ============================================================
    fun loadStories() {
        viewModelScope.launch {
            isStoriesLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val response = runCatching { api.getStoriesRaw() }.getOrNull()
                    if (response != null) {
                        val rawStr = response.string().trim()
                        val parsed = parseStories(rawStr)
                        if (!parsed.isNullOrEmpty()) {
                            val active = parsed.filter { !isStoryExpired(it) }
                            withContext(Dispatchers.Main) {
                                stories = active
                                active.forEach { s ->
                                    if (s.user != null) {
                                        userProfilesCache[s.userId] = s.user
                                        if (s.user.isVerified != null) {
                                            verifiedUsersMap[s.userId] = s.user.isUserVerified
                                        }
                                    }
                                }
                            }

                            // Fetch real user profiles for all story authors from the backend
                            val uniqueAuthorIds = active.map { it.userId }.distinct()
                            uniqueAuthorIds.forEach { uid ->
                                if (!verifiedUsersMap.containsKey(uid) || !userProfilesCache.containsKey(uid)) {
                                    val fetchedUser = fetchUserProfile(uid)
                                    if (fetchedUser != null) {
                                        withContext(Dispatchers.Main) {
                                            userProfilesCache[uid] = fetchedUser
                                            verifiedUsersMap[uid] = fetchedUser.isUserVerified
                                        }
                                    }
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                stories = emptyList()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            stories = emptyList()
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    stories = emptyList()
                }
            } finally {
                isStoriesLoading = false
            }
        }
    }

    private fun parseStories(rawJson: String): List<StoryType>? {
        return try {
            if (rawJson.startsWith("[")) {
                storyListAdapter.fromJson(rawJson)
            } else if (rawJson.startsWith("{")) {
                val obj = storiesResponseAdapter.fromJson(rawJson)
                if (obj?.stories != null) {
                    obj.stories
                } else {
                    null
                }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun recordStoryView(storyId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { api.recordStoryView(storyId) }
                }
                withContext(Dispatchers.Main) {
                    stories = stories.map {
                        if (it.id == storyId) it.copy(viewsCount = (it.viewsCount ?: 0) + 1) else it
                    }
                    if (viewingStory?.id == storyId) {
                        viewingStory = viewingStory?.let { it.copy(viewsCount = (it.viewsCount ?: 0) + 1) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun createStory(payload: CreateStoryPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val authHeader = userToken?.let { "Bearer $it" }
            val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())

            val userObj = currentUser ?: StoryUser(
                id = "user_${System.currentTimeMillis()}",
                name = "You",
                profileImageUrl = getDefaultStoryAvatar("You", "you")
            )

            val localStory = StoryType(
                id = "story_${System.currentTimeMillis()}",
                userId = userObj.id,
                slug = "story-${System.currentTimeMillis()}",
                type = payload.type,
                textContent = payload.textContent,
                backgroundStyle = payload.backgroundStyle,
                mediaUrl = payload.mediaUrl,
                mediaThumb = payload.mediaThumb ?: payload.mediaUrl,
                expiresAt = payload.expiresAt,
                createdAt = nowIso,
                viewsCount = 0,
                user = userObj
            )

            // Immediately reflect in UI reel on Main thread
            withContext(Dispatchers.Main) {
                stories = listOf(localStory) + stories.filter { it.id != localStory.id }
            }

            // Sync with backend API
            try {
                api.createStory(authHeader, payload)
                loadStories()
            } catch (_: Exception) {
                // Keep local story if remote is offline or preview
            }
            Unit
        }
    }

    suspend fun deleteStory(storyId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val authHeader = userToken?.let { "Bearer $it" }
            api.deleteStory(authHeader, storyId)
            withContext(Dispatchers.Main) {
                stories = stories.filter { it.id != storyId }
                if (viewingStory?.id == storyId) {
                    viewingStory = null
                }
            }
            Unit
        }
    }

    suspend fun uploadMedia(
        mainBytes: ByteArray,
        mainName: String,
        mainMime: String,
        thumbBytes: ByteArray,
        thumbName: String,
        thumbMime: String
    ): Result<UploadResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val authHeader = userToken?.let { "Bearer $it" }

            val fileReq = mainBytes.toRequestBody(mainMime.toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", mainName, fileReq)

            val thumbReq = thumbBytes.toRequestBody(thumbMime.toMediaTypeOrNull())
            val thumbPart = MultipartBody.Part.createFormData("thumbnail", thumbName, thumbReq)

            val nameReq = mainName.toRequestBody("text/plain".toMediaTypeOrNull())
            val namePart = MultipartBody.Part.createFormData("name", null, nameReq)

            val alt = "Story by ${currentUser?.name ?: "User"}"
            val altReq = alt.toRequestBody("text/plain".toMediaTypeOrNull())
            val altPart = MultipartBody.Part.createFormData("altText", null, altReq)

            val responseBody = api.uploadMedia(authHeader, filePart, thumbPart, namePart, altPart)
            val jsonStr = responseBody.string()
            val parsed = uploadResponseAdapter.fromJson(jsonStr)
            parsed ?: run {
                val json = JSONObject(jsonStr)
                UploadResponse(
                    url = json.optString("url", json.optString("dataUrl", null)),
                    thumbnailUrl = json.optString("thumbnail_url", json.optString("url", null)),
                    dataUrl = json.optString("dataUrl", null)
                )
            }
        }
    }

    suspend fun fetchAllCompaniesCatalog(): List<Company> = withContext(Dispatchers.IO) {
        try {
            val response = api.getAllCompaniesRaw(all = true).string()
            val list = mutableListOf<Company>()
            if (response.trim().startsWith("[")) {
                val array = org.json.JSONArray(response)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        Company(
                            id = obj.optString("id", "comp-$i"),
                            name = obj.optString("name", ""),
                            industry = obj.optString("industry", null),
                            logoUrl = obj.optString("logoUrl", null),
                            url = obj.optString("url", null),
                            description = obj.optString("description", null),
                            country = obj.optString("country", null)
                        )
                    )
                }
            } else {
                val root = org.json.JSONObject(response)
                val array = root.optJSONArray("companies") ?: org.json.JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        Company(
                            id = obj.optString("id", "comp-$i"),
                            name = obj.optString("name", ""),
                            industry = obj.optString("industry", null),
                            logoUrl = obj.optString("logoUrl", null),
                            url = obj.optString("url", null),
                            description = obj.optString("description", null),
                            country = obj.optString("country", null)
                        )
                    )
                }
            }
            if (list.isNotEmpty()) list.filter { it.name.isNotBlank() } else sampleCompanies
        } catch (_: Exception) {
            sampleCompanies
        }
    }

    suspend fun createCompany(jsonPayload: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val authHeader = userToken?.let { "Bearer $it" }
            val reqBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            api.createCompanyRaw(authHeader, reqBody).string()
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Failed to create company")
        }
    }

    suspend fun saveUserWork(companyName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val authHeader = userToken?.let { "Bearer $it" } ?: return@withContext false
            val json = JSONObject().apply { put("work", companyName.trim()) }
            val reqBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            api.updateProfile(authHeader, reqBody)
            withContext(Dispatchers.Main) {
                currentUser = currentUser?.copy(work = companyName.trim())
                prefs.edit().putString("user_work", companyName.trim()).apply()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun submitUserJob(
        jsonPayload: String,
        isEdit: Boolean,
        jobId: String?
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val authHeader = userToken?.let { "Bearer $it" } ?: return@withContext Pair(false, "User not authenticated")
            val reqBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())
            if (isEdit && !jobId.isNullOrBlank()) {
                val userId = currentUser?.id ?: ""
                api.updateUserJob(authHeader, userId, reqBody)
            } else {
                api.createUserJob(authHeader, reqBody)
            }
            withContext(Dispatchers.Main) {
                fetchDashboardData()
            }
            Pair(true, null)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Failed to submit job")
        }
    }
}


