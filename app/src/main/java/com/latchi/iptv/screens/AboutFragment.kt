package com.latchi.iptv.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import androidx.fragment.app.Fragment

import com.latchi.iptv.InternetSpeed.InternetSpeedActivity
import com.latchi.iptv.R
import com.latchi.iptv.databinding.FragmentAboutBinding
import android.widget.Toast


class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment using ViewBinding
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root



    }




    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)



        // Display app version
        binding.tvAppVersion.text = getAppVersion()



        binding.CardInternetSpeed.setOnClickListener {
            openInternetSpeedTester()
        }

        // Set click listeners for the cards
        binding.cardShare.setOnClickListener {
            Toast.makeText(requireContext(), "Share App clicked", Toast.LENGTH_LONG).show()

            shareApp()
        }

        binding.cardAppInfo.setOnClickListener {
            showAppInfo()
        }

        binding.cardUpdate.setOnClickListener {
            Toast.makeText(requireContext(), "Check for updates", Toast.LENGTH_LONG).show()
            openDownloadLink()
        }

        // Fetch live data and display in tvLiveData
        fetchLiveData()
    }

    private fun openInternetSpeedTester() {
        val intent = Intent(requireContext(), InternetSpeedActivity::class.java)
        startActivity(intent)
    }

    private fun fetchLiveData() {
        Toast.makeText(requireContext(), "Error 69", Toast.LENGTH_LONG).show()

    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo =
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = "Version: ${packageInfo.versionName} (${packageInfo.versionCode})"
            val buildId = "Build ID: ${packageInfo.versionCode}"
            val date = "Update Date: 2026-06-15"
            val commit = "Commit: stable-final"
            "$version\n$buildId\n$commit\n$date"
        } catch (e: PackageManager.NameNotFoundException) {
            "Version info not available"
        }
    }

    private fun shareApp() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "The Indian IPTV App is a comprehensive platform that allows users to stream over 500 Indian TV channels directly from their devices. The app provides a seamless streaming experience with a wide variety of channels, including news, entertainment, sports, movies, and regional content.\n\nDownload now: https://latchi.iptv.app"
            )
        }
        startActivity(Intent.createChooser(shareIntent, "Share App via"))
    }

    private fun showAppInfo() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_info, null)

        // Create and display a dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()
    }

    private fun openDownloadLink() {
        val url = "https://latchi.iptv.app"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
