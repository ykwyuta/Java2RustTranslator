package com.example.todo.web;

import com.example.todo.domain.Priority;
import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoFilter;
import com.example.todo.service.ActivityService;
import com.example.todo.service.TodoService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.jspecify.annotations.Nullable;

@Controller
@RequestMapping("/todos")
public class TodoController {

    private final TodoService todoService;
    private final ActivityService activityService;
    private final Clock clock;

    public TodoController(TodoService todoService, ActivityService activityService, Clock clock) {
        this.todoService = todoService;
        this.activityService = activityService;
        this.clock = clock;
    }

    /** フォームの優先度の選択肢（このコントローラのすべてのハンドラのモデルに入る）。 */
    @ModelAttribute("priorities")
    public Priority[] priorities() {
        return Priority.values();
    }

    @GetMapping
    public String list(
            @RequestParam(name = "filter", required = false) @Nullable String filterParam,
            @RequestParam(name = "q", required = false) @Nullable String keyword,
            Model model) {
        TodoFilter filter = TodoFilter.fromParam(filterParam);
        model.addAttribute("todos", todoService.findAll(filter, keyword));
        model.addAttribute("summary", todoService.summary());
        model.addAttribute("filter", filter.param());
        model.addAttribute("q", keyword == null ? "" : keyword);
        model.addAttribute("today", LocalDate.now(clock));
        model.addAttribute("activities", activityService.recent(5));
        return "todos/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("todoForm", new TodoForm());
        return "todos/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("todoForm") TodoForm form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "todos/form";
        }
        Todo todo = todoService.create(form.normalizedTitle(), form.normalizedDescription(), form.getDueDate(), form.getPriority());
        redirectAttributes.addFlashAttribute("message", "「" + todo.getTitle() + "」を追加しました");
        return "redirect:/todos";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable long id, Model model) {
        model.addAttribute("todoId", id);
        model.addAttribute("todoForm", TodoForm.from(todoService.findById(id)));
        return "todos/form";
    }

    @PostMapping("/{id}")
    public String update(
            @PathVariable long id,
            @Valid @ModelAttribute("todoForm") TodoForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("todoId", id);
            return "todos/form";
        }
        Todo todo = todoService.update(
                id, form.normalizedTitle(), form.normalizedDescription(), form.getDueDate(), form.getPriority(), form.isDone());
        redirectAttributes.addFlashAttribute("message", "「" + todo.getTitle() + "」を更新しました");
        return "redirect:/todos";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(
            @PathVariable long id,
            @RequestParam(name = "filter", required = false) @Nullable String filterParam,
            RedirectAttributes redirectAttributes) {
        todoService.toggle(id);
        redirectAttributes.addAttribute("filter", TodoFilter.fromParam(filterParam).param());
        return "redirect:/todos";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable long id, RedirectAttributes redirectAttributes) {
        Todo todo = todoService.delete(id);
        redirectAttributes.addFlashAttribute("message", "「" + todo.getTitle() + "」を削除しました");
        return "redirect:/todos";
    }

    @PostMapping("/completed/delete")
    public String deleteCompleted(RedirectAttributes redirectAttributes) {
        int count = todoService.deleteCompleted();
        redirectAttributes.addFlashAttribute("message", "完了済みの " + count + " 件を削除しました");
        return "redirect:/todos";
    }
}
