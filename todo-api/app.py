from fastapi import FastAPI, Depends, HTTPException
from pydantic import BaseModel
from auth import verify_api_key
from crud import (
    list_todos,
    get_todo,
    create_todo,
    update_todo,
    delete_todo,
)

app = FastAPI(title="Todo API", version="1.0.0")


class TodoCreate(BaseModel):
    title: str
    completed: bool = False


class TodoUpdate(BaseModel):
    title: str | None = None
    completed: bool | None = None


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/todos", dependencies=[Depends(verify_api_key)])
def read_todos():
    return list_todos()


@app.get("/todos/{todo_id}", dependencies=[Depends(verify_api_key)])
def read_todo(todo_id: int):
    todo = get_todo(todo_id)
    if not todo:
        raise HTTPException(status_code=404, detail="Todo not found")
    return todo


@app.post("/todos", status_code=201, dependencies=[Depends(verify_api_key)])
def add_todo(payload: TodoCreate):
    return create_todo(payload.title, payload.completed)


@app.put("/todos/{todo_id}", dependencies=[Depends(verify_api_key)])
def edit_todo(todo_id: int, payload: TodoUpdate):
    updated = update_todo(todo_id, payload.title, payload.completed)
    if not updated:
        raise HTTPException(status_code=404, detail="Todo not found")
    return updated


@app.delete("/todos/{todo_id}", status_code=204, dependencies=[Depends(verify_api_key)])
def remove_todo(todo_id: int):
    ok = delete_todo(todo_id)
    if not ok:
        raise HTTPException(status_code=404, detail="Todo not found")
    return None
