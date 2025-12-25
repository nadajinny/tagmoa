package com.ndjinny.tagmoa.controller

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.app.AlertDialog
import com.ndjinny.tagmoa.R
import com.ndjinny.tagmoa.model.MainTask
import com.ndjinny.tagmoa.model.SubTask
import com.ndjinny.tagmoa.model.Tag
import com.ndjinny.tagmoa.model.UserDatabase
import com.ndjinny.tagmoa.model.ensureManualScheduleFlag
import com.ndjinny.tagmoa.model.toSubTaskSafe
import com.ndjinny.tagmoa.view.MainTaskAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class MainTaskListFragment : Fragment(R.layout.fragment_main_task_list) {

    private lateinit var searchInput: TextInputEditText
    private lateinit var btnSearch: MaterialButton
    private lateinit var checkShowCompleted: MaterialCheckBox
    private lateinit var chipGroup: ChipGroup
    private lateinit var filterLabel: TextView
    private lateinit var btnFilterSettings: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyState: TextView

    private lateinit var tasksRef: DatabaseReference
    private lateinit var tagsRef: DatabaseReference
    private lateinit var subTasksRef: DatabaseReference

    private val adapter = MainTaskAdapter(
        onMoreClick = { task -> showTaskOptionsDialog(task) },
        onToggleMainComplete = { task, isChecked -> toggleMainTaskCompletion(task, isChecked) },
        onToggleSubTaskComplete = { subTask, isChecked -> toggleSubTaskCompletion(subTask, isChecked) }
    )

    private val allTasks = mutableListOf<MainTask>()
    private val tagMap = mutableMapOf<String, Tag>()
    private val selectedTagIds = mutableSetOf<String>()
    private val subTasksByMain = mutableMapOf<String, List<SubTask>>()
    private var showCompletedTasks: Boolean = false

    private var tasksListener: ValueEventListener? = null
    private var tagsListener: ValueEventListener? = null
    private var subTasksListener: ValueEventListener? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uid = requireUserIdOrRedirect() ?: return
        tasksRef = UserDatabase.tasksRef(uid)
        tagsRef = UserDatabase.tagsRef(uid)
        subTasksRef = UserDatabase.subTasksRef(uid)

        searchInput = view.findViewById(R.id.editSearchMainTask)
        btnSearch = view.findViewById(R.id.btnSearchMainTask)
        checkShowCompleted = view.findViewById(R.id.checkShowCompleted)
        chipGroup = view.findViewById(R.id.chipGroupTags)
        filterLabel = view.findViewById(R.id.textFilterLabel)
        btnFilterSettings = view.findViewById(R.id.btnOpenTagFilterSettings)
        recyclerView = view.findViewById(R.id.recyclerMainTaskList)
        progressBar = view.findViewById(R.id.progressMainList)
        emptyState = view.findViewById(R.id.textMainListEmpty)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        btnSearch.setOnClickListener { filterTasks() }
        btnFilterSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.containerMain, TagFilterManagementFragment())
                .addToBackStack("TagFilterManagement")
                .commit()
        }
        checkShowCompleted.setOnCheckedChangeListener { _, isChecked ->
            showCompletedTasks = isChecked
            updateCompletedToggleLabel()
            filterTasks()
        }
        updateCompletedToggleLabel()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterTasks()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        observeTags()
        observeTasks()
        observeSubTasks()
    }

    private fun observeTags() {
        tagsListener?.let { tagsRef.removeEventListener(it) }
        tagsListener = tagsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val tags = mutableMapOf<String, Tag>()
                for (child in snapshot.children) {
                    val tag = child.getValue(Tag::class.java) ?: continue
                    val tagId = tag.id.ifBlank { child.key.orEmpty() }
                    tag.id = tagId
                    tags[tagId] = tag
                }
                tagMap.clear()
                tagMap.putAll(tags)
                adapter.updateTags(tagMap)
                buildTagChips()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun buildTagChips() {
        chipGroup.removeAllViews()
        selectedTagIds.clear()
        val visibleTags = tagMap.values
            .filter { !it.hidden }
            .sortedWith(
                compareBy<Tag> { it.order }
                    .thenBy { it.name.lowercase(Locale.getDefault()) }
            )
        if (visibleTags.isEmpty()) {
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.message_no_tags)
                isEnabled = false
            }
            chipGroup.addView(chip)
            filterTasks()
            return
        }
        visibleTags.forEach { tag ->
            val chip = Chip(requireContext()).apply {
                text = tag.name
                isCheckable = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedTagIds.add(tag.id)
                    } else {
                        selectedTagIds.remove(tag.id)
                    }
                    filterTasks()
                }
            }
            chipGroup.addView(chip)
        }
        filterTasks()
    }

    private fun observeTasks() {
        tasksListener?.let { tasksRef.removeEventListener(it) }
        progressBar.visibility = View.VISIBLE
        tasksListener = tasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                progressBar.visibility = View.GONE
                allTasks.clear()
                for (child in snapshot.children) {
                    val task = child.getValue(MainTask::class.java) ?: continue
                    task.ensureManualScheduleFlag()
                    val taskId = task.id.ifBlank { child.key.orEmpty() }
                    task.id = taskId
                    allTasks.add(task)
                }
                filterTasks()
                context?.let { TaskReminderScheduler.syncMainTaskReminders(it, allTasks) }
            }

            override fun onCancelled(error: DatabaseError) {
                progressBar.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
            }
        })
    }

    private fun observeSubTasks() {
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
        subTasksListener = subTasksRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                subTasksByMain.clear()
                val totalSubTasks = mutableListOf<SubTask>()
                for (mainSnapshot in snapshot.children) {
                    val mainId = mainSnapshot.key.orEmpty()
                    val list = mutableListOf<SubTask>()
                    for (child in mainSnapshot.children) {
                        val subTask = child.toSubTaskSafe(mainId) ?: continue
                        list.add(subTask)
                    }
                    subTasksByMain[mainId] = list
                    totalSubTasks.addAll(list)
                }
                filterTasks()
                context?.let { TaskReminderScheduler.syncSubTaskReminders(it, totalSubTasks) }
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun filterTasks() {
        val query = searchInput.text?.toString().orEmpty().trim().lowercase(Locale.getDefault())

        val filtered = allTasks
            .asSequence()
            .filter { showCompletedTasks || !it.isCompleted }
            .filter { task ->
                if (query.isEmpty()) true
                else {
                    task.title.lowercase(Locale.getDefault()).contains(query) ||
                        task.description.lowercase(Locale.getDefault()).contains(query)
                }
            }
            .filter { task ->
                if (selectedTagIds.isEmpty()) true
                else selectedTagIds.all { task.tagIds.contains(it) }
            }
            .sortedWith(
                compareBy<MainTask> { it.dueDate ?: Long.MAX_VALUE }
                    .thenBy { it.startDate ?: Long.MAX_VALUE }
            )
            .toList()

        adapter.submitTasks(filtered, subTasksByMain)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCompletedToggleLabel() {
        checkShowCompleted.apply {
            isChecked = showCompletedTasks
            text = getString(
                if (showCompletedTasks) R.string.action_hide_completed else R.string.action_show_completed
            )
        }
    }

    private fun toggleMainTaskCompletion(task: MainTask, isCompleted: Boolean) {
        if (task.id.isBlank()) return

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
        filterTasks()

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

    private fun showTaskOptionsDialog(task: MainTask) {
        val items = arrayOf(
            getString(R.string.action_open_detail),
            getString(R.string.action_edit_task),
            getString(R.string.action_delete_task)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(task.title.ifBlank { getString(R.string.label_no_title) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        if (task.id.isBlank()) return@setItems
                        val intent = Intent(requireContext(), MainTaskDetailActivity::class.java).apply {
                            putExtra(MainTaskDetailActivity.EXTRA_TASK_ID, task.id)
                        }
                        startActivity(intent)
                    }
                    1 -> {
                        val intent = Intent(requireContext(), AddEditMainTaskActivity::class.java).apply {
                            putExtra(AddEditMainTaskActivity.EXTRA_TASK_ID, task.id)
                        }
                        startActivity(intent)
                    }
                    2 -> confirmDeleteTask(task)
                }
            }
            .show()
    }

    private fun confirmDeleteTask(task: MainTask) {
        if (task.id.isBlank()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.title_delete_task)
            .setMessage(R.string.message_delete_task)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteTask(task) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTask(task: MainTask) {
        val taskId = task.id
        if (taskId.isBlank()) return
        tasksRef.child(taskId).removeValue()
            .addOnSuccessListener {
                subTasksRef.child(taskId).removeValue()
                context?.let { ctx ->
                    Toast.makeText(ctx, R.string.message_task_deleted, Toast.LENGTH_SHORT).show()
                }
            }
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

    override fun onDestroyView() {
        super.onDestroyView()
        tasksListener?.let { tasksRef.removeEventListener(it) }
        tagsListener?.let { tagsRef.removeEventListener(it) }
        subTasksListener?.let { subTasksRef.removeEventListener(it) }
    }
}
