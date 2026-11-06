package nethical.digipaws.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nethical.digipaws.R
import nethical.digipaws.databinding.ActivityAddTimedActionActivityBinding
import nethical.digipaws.databinding.CheatHourItemBinding
import nethical.digipaws.databinding.DialogAddTimedActionBinding
import nethical.digipaws.services.AppBlockerService
import nethical.digipaws.utils.DaySchedule
import nethical.digipaws.utils.SavedPreferencesLoader
import nethical.digipaws.utils.TimeTools
import nl.joery.timerangepicker.TimeRangePicker
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class TimedActionActivity : AppCompatActivity() {

    companion object {
        const val MODE_APP_BLOCKER_CHEAT_HOURS = 1
        const val MODE_AUTO_FOCUS = 2
    }

    private lateinit var binding: ActivityAddTimedActionActivityBinding
    private val savedPreferencesLoader = SavedPreferencesLoader(this)
    private var timedActionList: MutableList<AutoTimedActionItem> = mutableListOf()

    private lateinit var selectUnblockedAppsLauncher: ActivityResultLauncher<Intent>
    private var selectedUnblockedApps: ArrayList<String>? = arrayListOf()

    private lateinit var dialogAddToTimedActionBinding: DialogAddTimedActionBinding

    private var selectedMode = MODE_APP_BLOCKER_CHEAT_HOURS
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityAddTimedActionActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (intent.hasExtra("selected_mode")) {
            selectedMode = intent.getIntExtra("selected_mode", MODE_APP_BLOCKER_CHEAT_HOURS)
        }

        when (selectedMode) {
            MODE_APP_BLOCKER_CHEAT_HOURS -> {
                timedActionList = savedPreferencesLoader.loadAppBlockerCheatHoursList()
            }

            MODE_AUTO_FOCUS -> timedActionList = savedPreferencesLoader.loadAutoFocusHoursList()
        }
        selectUnblockedAppsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                    selectedApps?.let {
                        selectedUnblockedApps = selectedApps
                        dialogAddToTimedActionBinding.btnSelectUnblockedApps.text =
                            getString(R.string.app_s_selected, selectedApps.size)
                    }
                }
            }

        binding.recyclerView2.layoutManager = LinearLayoutManager(this)
        binding.recyclerView2.adapter = CheatHourAdapter(timedActionList)

        binding.button.setOnClickListener {
            makeCheatHoursDialog()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onResume() {
        super.onResume()
        // "Active now" depends on the clock, so recompute it each time the list is shown.
        binding.recyclerView2.adapter?.notifyDataSetChanged()
    }

    /** Add a new entry, or edit [existing] in place when it is given. */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun makeCheatHoursDialog(existing: AutoTimedActionItem? = null) {


        dialogAddToTimedActionBinding = DialogAddTimedActionBinding.inflate(layoutInflater)

        dialogAddToTimedActionBinding.picker.startTime = TimeRangePicker.Time(6, 30)
        dialogAddToTimedActionBinding.picker.endTime = TimeRangePicker.Time(22, 0)
        if (existing != null) {
            dialogAddToTimedActionBinding.picker.startTime =
                TimeRangePicker.Time(existing.startTimeInMins / 60, existing.startTimeInMins % 60)
            dialogAddToTimedActionBinding.picker.endTime =
                TimeRangePicker.Time(existing.endTimeInMins / 60, existing.endTimeInMins % 60)
            dialogAddToTimedActionBinding.fromTime.text =
                dialogAddToTimedActionBinding.picker.startTime.toString()
            dialogAddToTimedActionBinding.endTime.text =
                dialogAddToTimedActionBinding.picker.endTime.toString()
            dialogAddToTimedActionBinding.cheatHourTitle.setText(existing.title)
            selectedUnblockedApps = ArrayList(existing.packages)
        }

        dialogAddToTimedActionBinding.picker.hourFormat = TimeRangePicker.HourFormat.FORMAT_24

        // One chip per weekday, Monday first. A new entry applies every day by default;
        // an edited one starts from its own days.
        val dayChips = DaySchedule.WEEK.associateWith { day ->
            Chip(this).apply {
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        context, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                    )
                )
                text = DaySchedule.shortName(day)
                isCheckable = true
                isChecked = existing == null || DaySchedule.appliesOn(existing.days, day)
                dialogAddToTimedActionBinding.dayChips.addView(this)
            }
        }
        var endTimeInMins: Int? = dialogAddToTimedActionBinding.picker.endTimeMinutes
        var startTimeInMins: Int? = dialogAddToTimedActionBinding.picker.startTimeMinutes

        dialogAddToTimedActionBinding.picker.setOnTouchListener { v, event ->
            // Disable ScrollView's touch interception when interacting with the picker
            when (event.action) {
                MotionEvent.ACTION_DOWN -> dialogAddToTimedActionBinding.scrollview.requestDisallowInterceptTouchEvent(
                    true
                )

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dialogAddToTimedActionBinding.scrollview.requestDisallowInterceptTouchEvent(
                    false
                )
            }
            v.onTouchEvent(event) // Pass the event to the picker
        }
        dialogAddToTimedActionBinding.picker.setOnTimeChangeListener(object :
            TimeRangePicker.OnTimeChangeListener {
            override fun onStartTimeChange(startTime: TimeRangePicker.Time) {
                dialogAddToTimedActionBinding.fromTime.text =
                    dialogAddToTimedActionBinding.picker.startTime.toString()
                startTimeInMins = dialogAddToTimedActionBinding.picker.startTimeMinutes
            }

            override fun onEndTimeChange(endTime: TimeRangePicker.Time) {
                dialogAddToTimedActionBinding.endTime.text =
                    dialogAddToTimedActionBinding.picker.endTime.toString()
                endTimeInMins = dialogAddToTimedActionBinding.picker.endTimeMinutes
            }

            override fun onDurationChange(duration: TimeRangePicker.TimeDuration) {
            }
        })

        when (selectedMode) {
            MODE_AUTO_FOCUS -> {
                dialogAddToTimedActionBinding.timedTitle.text = "Specify Auto-Focus Hours"
                dialogAddToTimedActionBinding.btnSelectUnblockedApps.text = "Select Apps to Block"
            }

            MODE_APP_BLOCKER_CHEAT_HOURS -> {
                dialogAddToTimedActionBinding.timedTitle.text = "Specify Cheat Hours"
                dialogAddToTimedActionBinding.timedTitle.text = "Specify Apps to Unblock"
            }
        }
        if (existing != null) {
            dialogAddToTimedActionBinding.btnSelectUnblockedApps.text =
                getString(R.string.app_s_selected, existing.packages.size)
        }

        dialogAddToTimedActionBinding.btnSelectUnblockedApps.setOnClickListener {
            val intent = Intent(this, SelectAppsActivity::class.java)
            intent.putStringArrayListExtra(
                "PRE_SELECTED_APPS",
                selectedUnblockedApps
            )

            if (selectedMode == MODE_APP_BLOCKER_CHEAT_HOURS) {
                intent.putStringArrayListExtra(
                    "APP_LIST",
                    ArrayList(savedPreferencesLoader.loadBlockedApps())
                )
            }

            selectUnblockedAppsLauncher.launch(intent)
        }
        MaterialAlertDialogBuilder(this)
            .setView(dialogAddToTimedActionBinding.root)
            .setPositiveButton(getString(if (existing == null) R.string.add else R.string.save)) { dialog, _ ->
                if (dialogAddToTimedActionBinding.cheatHourTitle.text?.isEmpty() == true) {
                    Toast.makeText(
                        this,
                        getString(R.string.please_type_a_title),
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (selectedUnblockedApps?.isEmpty() == true) {
                    Toast.makeText(
                        this,
                        getString(R.string.please_select_a_few_apps), Toast.LENGTH_SHORT
                    )
                        .show()
                } else if (dayChips.values.none { it.isChecked }) {
                    Toast.makeText(
                        this,
                        getString(R.string.please_select_a_day), Toast.LENGTH_SHORT
                    ).show()
                } else if (existing != null) {
                    // An edit is a new definition: a previous "End now" no longer applies.
                    replaceItem(
                        existing, existing.copy(
                            title = dialogAddToTimedActionBinding.cheatHourTitle.text.toString(),
                            startTimeInMins = startTimeInMins!!,
                            endTimeInMins = endTimeInMins!!,
                            packages = ArrayList(selectedUnblockedApps!!),
                            days = dayChips.filterValues { it.isChecked }.keys.toList(),
                            endedUntil = null
                        )
                    )
                    dialog.dismiss()
                } else {
                    timedActionList.add(
                        AutoTimedActionItem(
                            dialogAddToTimedActionBinding.cheatHourTitle.text.toString(),
                            startTimeInMins!!,
                            endTimeInMins!!,
                            selectedUnblockedApps!!,
                            days = dayChips.filterValues { it.isChecked }.keys.toList()
                        )
                    )
                    binding.recyclerView2.adapter?.notifyItemInserted(timedActionList.size)
                    saveList()
                    dialog.dismiss()
                }

            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }


    inner class CheatHourAdapter(
        private val items: List<AutoTimedActionItem>
    ) : RecyclerView.Adapter<CheatHourAdapter.CheatHourViewHolder>() {

        inner class CheatHourViewHolder(private val binding: CheatHourItemBinding) :
            RecyclerView.ViewHolder(binding.root) {

            @SuppressLint("SetTextI18n")
            fun bind(item: AutoTimedActionItem) {
                binding.cheatHourTitle.text = item.title
                val convertedStartTime = TimeTools.convertMinutesTo24Hour(item.startTimeInMins)
                val convertedEndTIme = TimeTools.convertMinutesTo24Hour(item.endTimeInMins)

                binding.editCheatHour.setOnClickListener { makeCheatHoursDialog(item) }
                binding.root.setOnClickListener { makeCheatHoursDialog(item) }

                binding.removeCheatHour.setOnClickListener {
                    timedActionList.removeAt(layoutPosition)
                    notifyItemRemoved(layoutPosition)
                    saveList()
                }

                binding.cheatTimings.text =
                    getString(
                        R.string.cheat_timings,
                        convertedStartTime.first,
                        convertedStartTime.second,
                        convertedEndTIme.first,
                        convertedEndTIme.second
                    ) + " · " + DaySchedule.label(item.days, getString(R.string.every_day))
                // Set, not append: a rebind (status change, onResume) would repeat the list.
                binding.selectedApps.text = item.packages.joinToString(" ")

                bindStatus(item)
            }

            private fun bindStatus(item: AutoTimedActionItem) {
                val now = Calendar.getInstance()
                val range = item.toRange()
                val endsAt = DaySchedule.currentEnd(range, now)
                // Only Auto-Focus can be ended early; cheat hours just show that they run.
                val canEnd = selectedMode == MODE_AUTO_FOCUS
                when {
                    endsAt != null -> {
                        binding.activeStatus.text =
                            getString(R.string.timed_action_active_until, formatTime(endsAt))
                        binding.activeStatus.visibility = View.VISIBLE
                        binding.endEarly.text = getString(R.string.end_now)
                        binding.endEarly.visibility = if (canEnd) View.VISIBLE else View.GONE
                        binding.endEarly.setOnClickListener { confirmEndEarly(item, endsAt) }
                    }

                    DaySchedule.isEndedEarly(range, now) -> {
                        binding.activeStatus.text = getString(R.string.timed_action_ended_early)
                        binding.activeStatus.visibility = View.VISIBLE
                        binding.endEarly.text = getString(R.string.resume)
                        binding.endEarly.visibility = if (canEnd) View.VISIBLE else View.GONE
                        binding.endEarly.setOnClickListener {
                            replaceItem(item, item.copy(endedUntil = null))
                        }
                    }

                    else -> {
                        binding.activeStatus.visibility = View.GONE
                        binding.endEarly.visibility = View.GONE
                    }
                }

            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheatHourViewHolder {
            val binding = CheatHourItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return CheatHourViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CheatHourViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private fun formatTime(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    private fun confirmEndEarly(item: AutoTimedActionItem, endsAt: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.end_timed_action_title, item.title))
            .setMessage(getString(R.string.end_timed_action_message, formatTime(endsAt)))
            .setPositiveButton(getString(R.string.end_now)) { _, _ ->
                replaceItem(item, item.copy(endedUntil = endsAt))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun replaceItem(old: AutoTimedActionItem, new: AutoTimedActionItem) {
        val index = timedActionList.indexOf(old)
        if (index < 0) return
        timedActionList[index] = new
        binding.recyclerView2.adapter?.notifyItemChanged(index)
        saveList()
        // Tell the running blocker now rather than when this screen is left.
        val action = when (selectedMode) {
            MODE_AUTO_FOCUS -> AppBlockerService.INTENT_ACTION_REFRESH_FOCUS_MODE
            else -> AppBlockerService.INTENT_ACTION_REFRESH_APP_BLOCKER
        }
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun saveList() {
        when (selectedMode) {
            MODE_APP_BLOCKER_CHEAT_HOURS -> savedPreferencesLoader.saveAppBlockerCheatHoursList(
                timedActionList
            )

            MODE_AUTO_FOCUS -> savedPreferencesLoader.saveAutoFocusHoursList(timedActionList)
        }
    }

    data class AutoTimedActionItem(
        val title: String,
        val startTimeInMins: Int,
        val endTimeInMins: Int,
        val packages: ArrayList<String>,
        val isProceedHidden: Boolean = false,
        // Calendar.DAY_OF_WEEK values; null (entries saved before weekdays) = every day.
        val days: List<Int>? = null,
        // Wall-clock millis until which this entry was ended early ("End now").
        val endedUntil: Long? = null
    ) {
        fun toRange() = DaySchedule.Range(startTimeInMins, endTimeInMins, days, endedUntil)
    }


}