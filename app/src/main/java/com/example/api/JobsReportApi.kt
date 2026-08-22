package com.example.api

import com.example.data.CategoryJobsResponse
import com.example.data.Company
import com.example.data.HomeResponse
import com.example.data.JobCategory
import com.example.data.JobOpportunity
import com.example.data.LocationInfo
import com.example.data.MarketReport
import com.example.data.MarketResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface JobsReportApi {
    @GET("api/home")
    suspend fun getHomeData(@Query("country") country: String): HomeResponse

    @GET("api/market")
    suspend fun getMarketData(@Query("limit") limit: Int = 5): MarketResponse

    @GET("api/categories")
    suspend fun getCategories(): List<JobCategory>

    @GET("api/companies-jobs")
    suspend fun getCompanies(): List<Company>

    @GET("api/company-jobs/{id}")
    suspend fun getCompanyJobs(@Path("id") companyId: String): List<JobOpportunity>

    @GET("api/category-jobs")
    suspend fun getCategoryJobs(@Query("category") category: String): CategoryJobsResponse

    @GET("api/reports")
    suspend fun getReports(): List<MarketReport>

    @GET("api/reports/{slug}")
    suspend fun getReportDetail(@Path("slug") slug: String): MarketReport

    @GET("api/locations")
    suspend fun getLocations(): List<LocationInfo>

    companion object {
        private const val BASE_URL = "https://jobsreport.online/"

        fun create(): JobsReportApi {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(JobsReportApi::class.java)
        }
    }
}
