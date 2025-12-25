package com.ndjinny.tagmoa.controller

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ndjinny.tagmoa.R
import com.ndjinny.tagmoa.model.MainTask
import com.ndjinny.tagmoa.model.SubTask
import com.ndjinny.tagmoa.model.UserDatabase
import com.ndjinny.tagmoa.model.ensureManualScheduleFlag
import com.ndjinny.tagmoa.model.toSubTaskSafe
import com.ndjinny.tagmoa.view.DueMainTaskAdapter
import com.ndjinny.tagmoa.view.DueSubTaskAdapter
import com.ndjinny.tagmoa.view.DueSubTaskItem
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var backgroundView: ImageView? = null
    private lateinit var recyclerMainDue: RecyclerView
    private lateinit var recyclerSubDue: RecyclerView
    private lateinit var textMainEmpty: TextView
    private lateinit var textSubEmpty: TextView
    private lateinit var textTodayDate: TextView

    private lateinit var mainAdapter: DueMainTaskAdapter
    private lateinit var subAdapter: DueSubTaskAdapter

    private lateinit var tasksRef: DatabaseReference
    private lateinit var subTasksRef: DatabaseReference

    private var tasksListener: ValueEventListener? = null
    private var subTasksListener: ValueEventListener? = null

    private val taskMap = mutableMapOf<String, MainTask>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uid = requireUserIdOrRedirect() ?: return
        tasksRef = UserDatabase.tasksRef(uid)
        subTasksRef = UserDatabase.subTasksRef(uid)

        backgroundView = view.findViewById(R.id.imageHomeBackground)
        recyclerMainDue = view.findViewById(R.id.recyclerHomeMainDue)
        recyclerSubDue = view.findViewById(R.id.recyclerHomeSubDue)
        textMainEmpty = view.findViewById(R.id.textHomeMainDueEmpty)
        textSubEmpty = view.findViewById(R.id.textHomeSubDueEmpty)
        textTodayDate = view.findViewById(R.id.textHomeTodayDate)

        mainAdapter = DueMainTaskAdapter(
            onClick = { mainTask ->
                if (mainTask.id.isBlank()) return@DueMainTaskAdapter
                val intent = Intent(requireContext(), MainTaskDetailActivity::class.java).apply {
                    putExtra(MainTaskDetailActivity.EXTRA_TASK_ID, mainTask.id)
                }
                startActivity(intent)
            },
            onLongClick = { task -> showCompleteMainDialog(task) }
        )
        subAdapter = DueSubTaskAdapter { item ->
            showCompleteSubDialog(item)
        }

        recyclerMainDue.layoutManager = LinearLayoutManager(requireContext())
        recyclerMainDue.adapter = mainAdapter

        recyclerSubDue.layoutManager = LinearLayoutManager(requireContext())
        recyclerSubDue.adapter = subAdapter

        updateTodayInfo()
        applySavedBackground()
        applySavedDateTextColor()
        observeTasks()
        observeSubTasks()
    }

    private fun updateTodayInfo() {
        val now = Calendar.getInstance().time
        val dateFormat = SimpleDateFormat("yyyy년 MM월 dd일", Locale.KOREA)
        textTodayDate.text = dateFormat.format(now)
    }

    private fun observeTasks() {
        tasksListener?.let { tasksRef.removeEventListener(it) }
        tasksListener = tasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val result = mutableListOf<MainTask>()
                taskMap.clear()
                for (child in snapshot.children) {
                    val task = child.getValue(MainTask::class.java) ?: continue
                    val taskId = task.id.ifBlank { child.key.orEmpty() }
                    task.ensureManualScheduleFlag()
                    task.id = taskId
                    taskMap[taskId] = task
                    if (isTaskDueToday(task)) {
                        result.add(task)
                    }
                }
                mainAdapter.submitList(result)
                textMainEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                textMainEmpty.visibility = View.VISIBLE
            }
        })
    }

    private fun observeSubTasks() {
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
        subTasksListener = subTasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val result = mutableListOf<DueSubTaskItem>()
                for (mainSnapshot in snapshot.children) {
                    val mainId = mainSnapshot.key.orEmpty()
                    for (child in mainSnapshot.children) {
                        val subTask = child.toSubTaskSafe(mainId) ?: continue
                        val subId = subTask.id.ifBlank { child.key.orEmpty() }
                        if (subTask.isCompleted) continue
                        if (isDueToday(subTask.dueDate)) {
                            val parentTitle = taskMap[mainId]?.title ?: getString(R.string.label_no_title)
                            result.add(
                                DueSubTaskItem(
                                    id = subId,
                                    mainTaskId = mainId,
                                    content = subTask.content,
                                    parentTitle = parentTitle,
                                    startDate = subTask.startDate,
                                    endDate = subTask.endDate,
                                    dueDate = subTask.dueDate,
                                    priority = subTask.priority
                                )
                            )
                        }
                    }
                }
                subAdapter.submitList(result)
                textSubEmpty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                textSubEmpty.visibility = View.VISIBLE
            }
        })
    }

    private fun isTaskDueToday(task: MainTask): Boolean {
        if (task.isCompleted) return false
        return isDueToday(task.dueDate)
    }

    private fun isDueToday(targetDate: Long?): Boolean {
        targetDate ?: return false
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val startMillis = startCal.timeInMillis
        val endMillis = endCal.timeInMillis
        return targetDate in startMillis..endMillis
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tasksListener?.let { tasksRef.removeEventListener(it) }
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
        backgroundView = null
    }

    private fun applySavedBackground() {
        if (backgroundView == null) return
        val uri = HomeBackgroundManager.getBackgroundUri(requireContext())
        if (uri != null) {
            applyBackground(uri)
        } else {
            resetToDefaultBackground()
        }
    }

    private fun applyBackground(uri: Uri) {
        val resolver = requireContext().contentResolver
        try {
            resolver.openInputStream(uri)?.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    backgroundView?.setImageBitmap(bitmap)
                } else {
                    throw IllegalArgumentException("Bitmap decode failed")
                }
            } ?: throw IllegalArgumentException("Unable to open stream")
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.home_background_load_failed), Toast.LENGTH_SHORT).show()
            HomeBackgroundManager.clearBackgroundUri(requireContext())
            resetToDefaultBackground()
        }
    }

    private fun resetToDefaultBackground() {
        backgroundView?.setImageResource(R.color.color_f2f6f6)
    }

    private fun applySavedDateTextColor() {
        val colorInt = HomeBackgroundManager.getDateTextColorInt(requireContext())
        textTodayDate.setTextColor(colorInt)
    }

    private fun showCompleteMainDialog(task: MainTask) {
        if (task.id.isBlank()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_complete_main)
            .setMessage(R.string.message_complete_main)
            .setPositiveButton(R.string.action_complete) { _, _ ->
                completeMainTask(task)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun completeMainTask(task: MainTask) {
        val id = task.id
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val updates = mapOf<String, Any?>(
            "isCompleted" to true,
            "completed" to true,
            "completedAt" to now
        )
        tasksRef.child(id).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), R.string.message_task_completed, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun showCompleteSubDialog(item: DueSubTaskItem) {
        if (item.id.isBlank() || item.mainTaskId.isBlank()) return
        val content = item.content.ifBlank { getString(R.string.label_no_title) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_complete_sub)
            .setMessage(content)
            .setPositiveButton(R.string.action_complete) { _, _ ->
                completeSubTask(item)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun completeSubTask(item: DueSubTaskItem) {
        val mainId = item.mainTaskId
        val subId = item.id
        if (mainId.isBlank() || subId.isBlank()) return
        val now = System.currentTimeMillis()
        val updates = mapOf<String, Any?>(
            "isCompleted" to true,
            "completed" to true,
            "completedAt" to now
        )
        subTasksRef.child(mainId).child(subId).updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(requireContext(), R.string.message_subtask_completed, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

}
