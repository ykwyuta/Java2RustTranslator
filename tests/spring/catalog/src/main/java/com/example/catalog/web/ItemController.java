package com.example.catalog.web;

import com.example.catalog.domain.Category;
import com.example.catalog.domain.Item;
import com.example.catalog.service.ItemService;
import com.example.catalog.service.SoldOutException;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
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

@Controller
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @ModelAttribute("categories")
    public Category[] categories() {
        return Category.values();
    }

    @GetMapping
    public String list(@RequestParam(name = "q", required = false) @Nullable String q, Model model) {
        List<Item> items = itemService.search(q, List.of());
        model.addAttribute("count", items.size());
        model.addAttribute("items", items);
        return "items/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("itemForm", new ItemForm());
        return "items/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("itemForm") ItemForm form, BindingResult bindingResult,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "items/form";
        }
        Item item = new Item();
        item.setName(form.getName() == null ? "" : form.getName());
        item.setCategory(form.getCategory());
        item.setPrice(form.getPrice());
        itemService.importAll(List.of(item));
        redirectAttributes.addFlashAttribute("message", "added");
        return "redirect:/items";
    }

    @PostMapping("/{id}/discount")
    public String discount(@PathVariable long id, RedirectAttributes redirectAttributes) throws SoldOutException {
        Item item = itemService.discount(id, 10);
        redirectAttributes.addFlashAttribute("message", "discounted " + item.getName());
        return "redirect:/items";
    }
}
