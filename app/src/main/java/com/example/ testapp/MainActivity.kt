package com.example.taskapp

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {
    private lateinit var taskAdapter: TaskAdapter
    private val tasks = mutableListOf<Task>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rvTasks = findViewById<RecyclerView>(R.id.rv_tasks)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fab_add)

        tasks.addAll(TaskStorage.loadTasks(this))

        taskAdapter = TaskAdapter(
            tasks,
            onTaskToggle = { task ->
                TaskStorage.saveTasks(this, tasks)
            },
            onTaskDelete = { task ->
                tasks.remove(task)
                taskAdapter.updateTasks(tasks)
                TaskStorage.saveTasks(this, tasks)
            }
        )
        rvTasks.adapter = taskAdapter

        fabAdd.setOnClickListener {
            showAddTaskDialog()
        }
    }

    private fun showAddTaskDialog() {
        val editText = EditText(this)
        editText.hint = "Nueva tarea"

        AlertDialog.Builder(this)
            .setTitle("Agregar tarea")
            .setView(editText)
            .setPositiveButton("Agregar") { dialog, _ ->
                val title = editText.text.toString().trim()
                if (title.isNotEmpty()) {
                    val newId = (tasks.maxByOrNull { it.id }?.id ?: 0) + 1
                    val task = Task(id = newId, title = title)
                    tasks.add(task)
                    taskAdapter.updateTasks(tasks)
                    TaskStorage.saveTasks(this, tasks)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
