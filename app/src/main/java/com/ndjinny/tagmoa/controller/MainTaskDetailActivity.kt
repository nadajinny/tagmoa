package com.ndjinny.tagmoa.controller

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ndjinny.tagmoa.R
import com.ndjinny.tagmoa.model.MainTask
import com.ndjinny.tagmoa.model.SubTask
import com.ndjinny.tagmoa.model.Tag
import com.ndjinny.tagmoa.model.UserDatabase
import com.ndjinny.tagmoa.model.TaskCompletionSyncManager
import com.ndjinny.tagmoa.model.ensureManualScheduleFlag
import com.ndjinny.tagmoa.model.toSubTaskSafe
import com.ndjinny.tagmoa.view.SubTaskAdapter
import com.ndjinny.tagmoa.view.buildScheduleLabel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

class MainTaskDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }

    private lateinit var tasksRef: DatabaseReference
    private lateinit var subTasksRef: DatabaseReference
    private lateinit var tagsRef: DatabaseReference
    private lateinit var userId: String

    private lateinit var textTitle: TextView
    private lateinit var textDate: TextView
    private lateinit var textTags: TextView
    private lateinit var textDescription: TextView
    private lateinit var colorView: View
    private lateinit var subTaskEmpty: TextView
    private lateinit var btnToggleComplete: Button

    private lateinit var adapter: SubTaskAdapter

    private var taskId: String = ""
    private var currentTask: MainTask? = null
    private var tagsMap: Map<String, Tag> = emptyMap()

    private var taskListener: ValueEventListener? = null
    private var subTaskListener: ValueEventListener? = null
    private var tagListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_task_detail)

        taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (taskId.isBlank()) {
            finish()
            return
        }

        val uid = requireUserIdOrRedirect() ?: return
        userId = uid
        tasksRef = UserDatabase.tasksRef(userId)
        subTasksRef = UserDatabase.subTasksRef(userId)
        tagsRef = UserDatabase.tagsRef(userId)
        TaskCompletionSyncManager.flushPending(userId, tasksRef)

        textTitle = findViewById(R.id.textDetailTitle)
        textDate = findViewById(R.id.textDetailDate)
        textTags = findViewById(R.id.textDetailTags)
        textDescription = findViewById(R.id.textDetailDescription)
        colorView = findViewById(R.id.viewDetailColor)
        subTaskEmpty = findViewById(R.id.textSubTaskEmpty)

        val recyclerSubTasks = findViewById<RecyclerView>(R.id.recyclerSubTasks)
        val btnEditTask = findViewById<Button>(R.id.btnEditTask)
        val btnDeleteTask = findViewById<Button>(R.id.btnDeleteTask)
        val btnAddSubTask = findViewById<Button>(R.id.btnAddSubTask)
        btnToggleComplete = findViewById(R.id.btnToggleTaskComplete)

        adapter = SubTaskAdapter(
            onEdit = { subTask ->
                val intent = Intent(this, AddEditSubTaskActivity::class.java)
                intent.putExtra(AddEditSubTaskActivity.EXTRA_MAIN_TASK_ID, taskId)
                intent.putExtra(AddEditSubTaskActivity.EXTRA_SUB_TASK_ID, subTask.id)
                startActivity(intent)
            },
            onDelete = { subTask -> confirmDeleteSubTask(subTask) },
            onToggleComplete = { subTask, isChecked ->
                toggleSubTaskCompletion(subTask, isChecked)
            }
        )

        recyclerSubTasks.layoutManager = LinearLayoutManager(this)
        recyclerSubTasks.adapter = adapter

        btnEditTask.setOnClickListener {
            val intent = Intent(this, AddEditMainTaskActivity::class.java)
            intent.putExtra(AddEditMainTaskActivity.EXTRA_TASK_ID, taskId)
            startActivity(intent)
        }

        btnDeleteTask.setOnClickListener { confirmDeleteTask() }
        btnAddSubTask.setOnClickListener {
            val intent = Intent(this, AddEditSubTaskActivity::class.java)
            intent.putExtra(AddEditSubTaskActivity.EXTRA_MAIN_TASK_ID, taskId)
            startActivity(intent)
        }
        btnToggleComplete.setOnClickListener { toggleMainTaskCompletion() }

        observeTags()
        observeTask()
        observeSubTasks()
    }

    private fun observeTags() {
        tagListener?.let { tagsRef.removeEventListener(it) }
        tagListener = tagsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                tagsMap = snapshot.children.mapNotNull { child ->
                    val tag = child.getValue(Tag::class.java)
                    tag?.apply { id = id.ifBlank { child.key.orEmpty() } }
                }.associateBy { it.id }
                renderTask()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun observeTask() {
        taskListener?.let { tasksRef.child(taskId).removeEventListener(it) }
        taskListener = tasksRef.child(taskId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val task = snapshot.getValue(MainTask::class.java)
                task?.ensureManualScheduleFlag()
                currentTask = task
                currentTask?.id = taskId
                renderTask()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun observeSubTasks() {
        subTaskListener?.let { subTasksRef.child(taskId).removeEventListener(it) }
        subTaskListener = subTasksRef.child(taskId).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val items = mutableListOf<SubTask>()
                for (child in snapshot.children) {
                    val subTask = child.toSubTaskSafe(taskId) ?: continue
                    items.add(subTask)
                }
                adapter.submitList(items)
                subTaskEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                updateAutoDueDate(items)
            }

            override fun onCancelled(error: DatabaseError) {
                subTaskEmpty.visibility = View.VISIBLE
            }
        })
    }

    private fun renderTask() {
        val task = currentTask ?: return
        textTitle.text = task.title.ifBlank { getString(R.string.label_no_title) }
        textDate.text = task.buildScheduleLabel(this)
        val tagNames = task.tagIds.mapNotNull { tagsMap[it]?.name }
        textTags.text = if (tagNames.isEmpty()) getString(R.string.label_no_tags) else tagNames.joinToString(", ")
        textDescription.text = task.description.ifBlank { getString(R.string.label_no_description) }
        val parsedColor = try {
            Color.parseColor(task.mainColor)
        } catch (e: IllegalArgumentException) {
            ContextCompat.getColor(this, R.color.brand_primary)
        }
        colorView.setBackgroundColor(parsedColor)
        btnToggleComplete.text = if (task.isCompleted) {
            getString(R.string.action_mark_task_incomplete)
        } else {
            getString(R.string.action_mark_task_complete)
        }
    }

    private fun confirmDeleteTask() {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_delete_task)
            .setMessage(R.string.message_delete_task)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteTask() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteTask() {
        tasksRef.child(taskId).removeValue()
            .addOnSuccessListener {
                subTasksRef.child(taskId).removeValue()
                Toast.makeText(this, R.string.message_task_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun confirmDeleteSubTask(subTask: SubTask) {
        AlertDialog.Builder(this)
            .setTitle(R.string.title_delete_subtask)
            .setMessage(R.string.message_delete_subtask)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteSubTask(subTask) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSubTask(subTask: SubTask) {
        if (subTask.id.isBlank()) return
        subTasksRef.child(taskId).child(subTask.id).removeValue()
            .addOnSuccessListener {
                Toast.makeText(this, R.string.message_subtask_deleted, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun toggleMainTaskCompletion() {
        val task = currentTask ?: return
        val newStatus = !task.isCompleted
        val newCompletedAt = if (newStatus) System.currentTimeMillis() else null
        val affectsDueDate = !task.manualSchedule
        val updatedDueDate = if (affectsDueDate) {
            if (newStatus) task.dueDate ?: System.currentTimeMillis() else null
        } else {
            task.dueDate
        }

        val originalCompletedAt = task.completedAt
        val originalDueDate = task.dueDate

        task.isCompleted = newStatus
        task.completedAt = newCompletedAt
        if (affectsDueDate) {
            task.dueDate = updatedDueDate
        }
        renderTask()

        TaskCompletionSyncManager.enqueue(
            userId,
            taskId,
            TaskCompletionSyncManager.CompletionPayload(
                isCompleted = newStatus,
                completedAt = newCompletedAt,
                dueDate = updatedDueDate,
                updatesDueDate = affectsDueDate
            )
        )

        val updates = mutableMapOf<String, Any?>(
            "isCompleted" to newStatus,
            "completed" to newStatus,
            "completedAt" to newCompletedAt
        )
        if (affectsDueDate) {
            updates["dueDate"] = updatedDueDate
        }
        tasksRef.child(taskId).updateChildren(updates)
            .addOnSuccessListener {
                val message = if (newStatus) {
                    R.string.message_task_marked_complete
                } else {
                    R.string.message_task_marked_incomplete
                }
                TaskCompletionSyncManager.markSynced(userId, taskId)
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { error ->
                task.isCompleted = !newStatus
                task.completedAt = originalCompletedAt
                if (affectsDueDate) {
                    task.dueDate = originalDueDate
                }
                renderTask()
                TaskCompletionSyncManager.markSynced(userId, taskId)
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun toggleSubTaskCompletion(subTask: SubTask, isCompleted: Boolean) {
        if (subTask.id.isBlank()) return
        val updates = mutableMapOf<String, Any?>(
            "isCompleted" to isCompleted,
            "completed" to isCompleted,
            "completedAt" to if (isCompleted) System.currentTimeMillis() else null
        )
        subTasksRef.child(taskId).child(subTask.id).updateChildren(updates)
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun updateAutoDueDate(subTasks: List<SubTask>) {
        val task = currentTask ?: return
        if (task.manualSchedule || task.isCompleted) return

        val hasSubTasks = subTasks.isNotEmpty()
        val allCompleted = hasSubTasks && subTasks.all { it.isCompleted }
        val desiredDueDate = if (allCompleted) {
            val completionDates = subTasks.mapNotNull { if (it.isCompleted) it.completedAt else null }
            completionDates.maxOrNull() ?: System.currentTimeMillis()
        } else {
            null
        }

        val shouldClearDue = !allCompleted && task.dueDate != null
        val shouldUpdateDue = allCompleted && task.dueDate != desiredDueDate

        if (!shouldClearDue && !shouldUpdateDue) return

        val updates = mutableMapOf<String, Any?>(
            "dueDate" to desiredDueDate
        )
        tasksRef.child(taskId).updateChildren(updates)
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    error.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        taskListener?.let { tasksRef.child(taskId).removeEventListener(it) }
        subTaskListener?.let { subTasksRef.child(taskId).removeEventListener(it) }
        tagListener?.let { tagsRef.removeEventListener(it) }
    }
}
