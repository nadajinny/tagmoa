package com.ndjinny.tagmoa.controller

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ndjinny.tagmoa.R
import com.ndjinny.tagmoa.model.MainTask
import com.ndjinny.tagmoa.model.SubTask
import com.ndjinny.tagmoa.model.UserDatabase
import com.ndjinny.tagmoa.model.toSubTaskSafe
import com.ndjinny.tagmoa.model.ensureManualScheduleFlag
import com.ndjinny.tagmoa.view.CalendarDecorators
import com.ndjinny.tagmoa.view.CalendarScheduleAdapter
import com.ndjinny.tagmoa.view.FurangCalendar
import com.ndjinny.tagmoa.view.overlapsWithRange
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.prolificinteractive.materialcalendarview.CalendarDay
import com.prolificinteractive.materialcalendarview.DayViewDecorator
import com.prolificinteractive.materialcalendarview.MaterialCalendarView
import com.prolificinteractive.materialcalendarview.format.ArrayWeekDayFormatter
import java.util.Calendar

class CalendarFragment : Fragment(R.layout.fragment_calendar) {

    private lateinit var calendarView: MaterialCalendarView
    private lateinit var recyclerPending: RecyclerView
    private lateinit var recyclerCompleted: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var dateText: TextView
    private lateinit var pendingLabel: TextView
    private lateinit var completedLabel: TextView
    private lateinit var pendingCard: View
    private lateinit var completedCard: View

    private lateinit var pendingAdapter: CalendarScheduleAdapter
    private lateinit var completedAdapter: CalendarScheduleAdapter

    private lateinit var tasksRef: DatabaseReference
    private lateinit var subTasksRef: DatabaseReference
    private var tasksListener: ValueEventListener? = null
    private var subTasksListener: ValueEventListener? = null

    private lateinit var dayDecorator: DayViewDecorator
    private lateinit var todayDecorator: DayViewDecorator
    private lateinit var sundayDecorator: DayViewDecorator
    private lateinit var saturdayDecorator: DayViewDecorator
    private lateinit var selectedMonthDecorator: DayViewDecorator
    private var eventDecorator: DayViewDecorator? = null

    private val allTasks = mutableListOf<MainTask>()
    private val subTasksByMain = mutableMapOf<String, MutableList<SubTask>>()
    private var selectedDay: CalendarDay = CalendarDay.today()
    private lateinit var userId: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uid = requireUserIdOrRedirect() ?: return
        userId = uid
        tasksRef = UserDatabase.tasksRef(uid)
        subTasksRef = UserDatabase.subTasksRef(uid)

        calendarView = view.findViewById(R.id.calendarView)
        recyclerPending = view.findViewById(R.id.recyclerCalendarSchedulePending)
        recyclerCompleted = view.findViewById(R.id.recyclerCalendarScheduleCompleted)
        emptyState = view.findViewById(R.id.textCalendarEmpty)
        dateText = view.findViewById(R.id.textCalendarSelectedDate)
        pendingCard = view.findViewById(R.id.cardCalendarPending)
        completedCard = view.findViewById(R.id.cardCalendarCompleted)
        pendingLabel = view.findViewById(R.id.textCalendarSchedulePendingLabel)
        completedLabel = view.findViewById(R.id.textCalendarScheduleCompletedLabel)

        pendingAdapter = CalendarScheduleAdapter(
            onItemLongClick = { openTaskDetail(it) },
            onToggleComplete = { task, isChecked -> toggleMainTaskCompletion(task, isChecked) },
            onToggleSubTaskComplete = { subTask, isChecked -> toggleSubTaskCompletion(subTask, isChecked) },
            onMoreClick = { task -> showTaskOptionsDialog(task) }
        )
        completedAdapter = CalendarScheduleAdapter(
            onItemLongClick = { openTaskDetail(it) },
            onToggleComplete = { task, isChecked -> toggleMainTaskCompletion(task, isChecked) },
            onToggleSubTaskComplete = { subTask, isChecked -> toggleSubTaskCompletion(subTask, isChecked) },
            onMoreClick = { task -> showTaskOptionsDialog(task) }
        )

        recyclerPending.layoutManager = LinearLayoutManager(requireContext())
        recyclerPending.adapter = pendingAdapter
        recyclerPending.isNestedScrollingEnabled = false

        recyclerCompleted.layoutManager = LinearLayoutManager(requireContext())
        recyclerCompleted.adapter = completedAdapter
        recyclerCompleted.isNestedScrollingEnabled = false

        setupCalendarView()
        observeTasks()
        observeSubTasks()
    }

    private fun setupCalendarView() {
        dayDecorator = CalendarDecorators.dayDecorator(requireContext())
        todayDecorator = CalendarDecorators.todayDecorator(requireContext())
        sundayDecorator = CalendarDecorators.sundayDecorator(requireContext())
        saturdayDecorator = CalendarDecorators.saturdayDecorator(requireContext())
        selectedMonthDecorator = CalendarDecorators.selectedMonthDecorator(requireContext(), selectedDay.month)

        calendarView.addDecorators(
            dayDecorator,
            todayDecorator,
            sundayDecorator,
            saturdayDecorator,
            selectedMonthDecorator
        )
        calendarView.setWeekDayFormatter(ArrayWeekDayFormatter(resources.getTextArray(R.array.custom_weekdays)))
        calendarView.setHeaderTextAppearance(R.style.CalendarWidgetHeader)
        calendarView.setDateSelected(selectedDay, true)
        updateSelectedDate(selectedDay)
        calendarView.doOnLayout {
            val availableWidth = it.width - it.paddingLeft - it.paddingRight
            if (availableWidth > 0) {
                val tileWidth = availableWidth / FurangCalendar.DAYS_OF_WEEK
                if (tileWidth > 0) {
                    calendarView.setTileWidth(tileWidth)
                    calendarView.setTileHeight(tileWidth)
                }
            }
        }

        calendarView.setOnMonthChangedListener { widget, date ->
            updateSelectedMonthDecorator(date.month)
            widget.clearSelection()
            val firstDay = CalendarDay.from(date.year, date.month, 1)
            widget.setDateSelected(firstDay, true)
            updateSelectedDate(firstDay)
        }
        calendarView.setOnDateChangedListener { _, date, selected ->
            if (selected) {
                updateSelectedDate(date)
            }
        }
    }

    private fun updateSelectedMonthDecorator(month: Int) {
        calendarView.removeDecorator(selectedMonthDecorator)
        selectedMonthDecorator = CalendarDecorators.selectedMonthDecorator(requireContext(), month)
        calendarView.addDecorator(selectedMonthDecorator)
    }

    private fun updateSelectedDate(date: CalendarDay) {
        selectedDay = date
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.month - 1)
            set(Calendar.DAY_OF_MONTH, date.day)
        }
        val dayLabel = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> getString(R.string.calendar_week_mon)
            Calendar.TUESDAY -> getString(R.string.calendar_week_tue)
            Calendar.WEDNESDAY -> getString(R.string.calendar_week_wed)
            Calendar.THURSDAY -> getString(R.string.calendar_week_thu)
            Calendar.FRIDAY -> getString(R.string.calendar_week_fri)
            Calendar.SATURDAY -> getString(R.string.calendar_week_sat)
            Calendar.SUNDAY -> getString(R.string.calendar_week_sun)
            else -> ""
        }
        dateText.text = getString(R.string.calendar_selected_date_format, date.month, date.day, dayLabel)
        filterSchedulesForSelectedDate()
    }

    private fun observeTasks() {
        tasksListener?.let { tasksRef.removeEventListener(it) }
        tasksListener = tasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tasks = mutableListOf<MainTask>()
                for (child in snapshot.children) {
                    val task = child.getValue(MainTask::class.java) ?: continue
                    val taskId = task.id.ifBlank { child.key.orEmpty() }
                    task.id = taskId
                    task.ensureManualScheduleFlag()
                    tasks.add(task)
                }
                allTasks.clear()
                allTasks.addAll(tasks)
                refreshEventDecorator()
                filterSchedulesForSelectedDate()
            }

            override fun onCancelled(error: DatabaseError) {
                allTasks.clear()
                pendingAdapter.submitList(emptyList(), emptyMap())
                completedAdapter.submitList(emptyList(), emptyMap())
                emptyState.visibility = View.VISIBLE
                pendingLabel.visibility = View.GONE
                recyclerPending.visibility = View.GONE
                completedLabel.visibility = View.GONE
                recyclerCompleted.visibility = View.GONE
            }
        })
    }

    private fun observeSubTasks() {
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
        subTasksListener = subTasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                subTasksByMain.clear()
                for (mainSnapshot in snapshot.children) {
                    val mainId = mainSnapshot.key.orEmpty()
                    val list = mutableListOf<SubTask>()
                    for (child in mainSnapshot.children) {
                        val subTask = child.toSubTaskSafe(mainId) ?: continue
                        list.add(subTask)
                    }
                    subTasksByMain[mainId] = list
                }
                filterSchedulesForSelectedDate()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun refreshEventDecorator() {
        if (!this::calendarView.isInitialized) return
        eventDecorator?.let { calendarView.removeDecorator(it) }
        if (allTasks.isEmpty()) {
            eventDecorator = null
            calendarView.invalidateDecorators()
            return
        }
        eventDecorator = CalendarDecorators.eventDecorator(requireContext(), allTasks)
        calendarView.addDecorator(eventDecorator)
        calendarView.invalidateDecorators()
    }

    private fun filterSchedulesForSelectedDate() {
        val targetStart = selectedDayAtStartMillis()
        val targetEnd = selectedDayAtEndMillis()
        val filtered = allTasks.filter { task ->
            overlapsWithRange(task.startDate, task.endDate, targetStart, targetEnd, task.dueDate)
        }

        val pendingTasks = filtered
            .filter { !it.isCompleted }
            .sortedWith(
                compareBy<MainTask> { it.dueDate ?: Long.MAX_VALUE }
                    .thenBy { it.startDate ?: Long.MAX_VALUE }
            )

        val completedTasks = filtered
            .filter { it.isCompleted }
            .sortedByDescending { it.completedAt ?: 0L }

        val subTaskMap = mutableMapOf<String, List<SubTask>>()
        (pendingTasks + completedTasks).forEach { task ->
            if (task.id.isNotBlank()) {
                subTaskMap[task.id] = getRelevantSubTasks(task.id)
            }
        }

        pendingAdapter.submitList(pendingTasks, subTaskMap)
        completedAdapter.submitList(completedTasks, subTaskMap)

        val hasPending = pendingTasks.isNotEmpty()
        val hasCompleted = completedTasks.isNotEmpty()

        pendingCard.visibility = if (hasPending) View.VISIBLE else View.GONE
        completedCard.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        emptyState.visibility = if (hasPending || hasCompleted) View.GONE else View.VISIBLE
    }

    private fun selectedDayAtStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(selectedDay.year, selectedDay.month - 1, selectedDay.day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun selectedDayAtEndMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(selectedDay.year, selectedDay.month - 1, selectedDay.day, 23, 59, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tasksListener?.let { tasksRef.removeEventListener(it) }
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
    }

    private fun openTaskDetail(task: MainTask) {
        if (task.id.isBlank()) return
        val intent = Intent(requireContext(), MainTaskDetailActivity::class.java).apply {
            putExtra(MainTaskDetailActivity.EXTRA_TASK_ID, task.id)
        }
        startActivity(intent)
    }

    private fun showTaskOptionsDialog(task: MainTask) {
        val options = arrayOf(
            getString(R.string.action_edit),
            getString(R.string.action_delete)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(task.title.ifBlank { getString(R.string.label_no_title) })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openTaskDetail(task)
                    1 -> confirmDeleteTaskFromCalendar(task)
                }
            }
            .show()
    }

    private fun confirmDeleteTaskFromCalendar(task: MainTask) {
        if (task.id.isBlank()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_delete_task)
            .setMessage(R.string.message_delete_task)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleteTaskFromCalendar(task)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTaskFromCalendar(task: MainTask) {
        val taskId = task.id
        if (taskId.isBlank()) return
        tasksRef.child(taskId).removeValue()
            .addOnSuccessListener {
                subTasksRef.child(taskId).removeValue()
                Toast.makeText(
                    requireContext(),
                    R.string.message_task_deleted,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    requireContext(),
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun getRelevantSubTasks(mainTaskId: String): List<SubTask> {
        val dayStart = selectedDayAtStartMillis()
        val dayEnd = selectedDayAtEndMillis()
        return subTasksByMain[mainTaskId].orEmpty()
            .filter {
                overlapsWithRange(it.startDate, it.endDate, dayStart, dayEnd, it.dueDate) || !it.isCompleted
            }
            .sortedWith(
                compareBy<SubTask> { it.isCompleted }
                    .thenBy { it.startDate ?: it.dueDate ?: Long.MAX_VALUE }
            )
    }

    private fun toggleMainTaskCompletion(task: MainTask, isCompleted: Boolean) {
        if (task.id.isBlank()) return
        if (!this::userId.isInitialized) return

        val originalIsCompleted = task.isCompleted
        val originalCompletedAt = task.completedAt
        val originalDueDate = task.dueDate

        val newCompletedAt = if (isCompleted) System.currentTimeMillis() else null
        val affectsDueDate = !task.manualSchedule
        val updatedDueDate = if (affectsDueDate) {
            if (isCompleted) task.dueDate ?: System.currentTimeMillis() else null
        } else {
            task.dueDate
        }
        task.isCompleted = isCompleted
        task.completedAt = newCompletedAt
        if (affectsDueDate) {
            task.dueDate = updatedDueDate
        }
        updateCachedTask(task.id, isCompleted, newCompletedAt, if (affectsDueDate) updatedDueDate else task.dueDate)
        filterSchedulesForSelectedDate()

        val updates = mutableMapOf<String, Any?>(
            "isCompleted" to isCompleted,
            "completed" to isCompleted,
            "completedAt" to newCompletedAt
        )
        if (affectsDueDate) {
            updates["dueDate"] = updatedDueDate
        }
        tasksRef.child(task.id).updateChildren(updates)
            .addOnSuccessListener {
                val message = if (isCompleted) {
                    R.string.message_task_marked_complete
                } else {
                    R.string.message_task_marked_incomplete
                }
                context?.let { ctx -> Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show() }
            }
            .addOnFailureListener { error ->
                task.isCompleted = originalIsCompleted
                task.completedAt = originalCompletedAt
                task.dueDate = originalDueDate
                updateCachedTask(task.id, originalIsCompleted, originalCompletedAt, originalDueDate)
                filterSchedulesForSelectedDate()
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        error.message ?: getString(R.string.error_generic),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun toggleSubTaskCompletion(subTask: SubTask, isCompleted: Boolean) {
        if (subTask.id.isBlank()) return
        val mainId = subTask.mainTaskId
        if (mainId.isBlank()) return
        val updates = mapOf<String, Any?>(
            "isCompleted" to isCompleted,
            "completed" to isCompleted,
            "completedAt" to if (isCompleted) System.currentTimeMillis() else null
        )
        subTasksRef.child(mainId).child(subTask.id).updateChildren(updates)
            .addOnFailureListener { error ->
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        error.message ?: getString(R.string.error_generic),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun updateCachedTask(
        taskId: String,
        isCompleted: Boolean,
        completedAt: Long?,
        dueDate: Long?
    ) {
        val index = allTasks.indexOfFirst { it.id == taskId }
        if (index == -1) return
        val cached = allTasks[index]
        cached.isCompleted = isCompleted
        cached.completedAt = completedAt
        cached.dueDate = dueDate
    }
}
