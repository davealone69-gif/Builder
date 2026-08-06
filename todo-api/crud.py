from typing import Optional
from models import Todo

_todos: list[Todo] = []
_next_id = 1


def list_todos():
    return _todos


def get_todo(todo_id: int) -> Optional[Todo]:
    return next((t for t in _todos if t.id == todo_id), None)


def create_todo(title: str, completed: bool = False) -> Todo:
    global _next_id
    todo = Todo(id=_next_id, title=title, completed=completed)
    _todos.append(todo)
    _next_id += 1
    return todo


def update_todo(todo_id: int, title: str | None, completed: bool | None) -> Optional[Todo]:
    todo = get_todo(todo_id)
    if not todo:
        return None
    if title is not None:
        todo.title = title
    if completed is not None:
        todo.completed = completed
    return todo


def delete_todo(todo_id: int) -> bool:
    global _todos
    before = len(_todos)
    _todos = [t for t in _todos if t.id != todo_id]
    return len(_todos) < before
