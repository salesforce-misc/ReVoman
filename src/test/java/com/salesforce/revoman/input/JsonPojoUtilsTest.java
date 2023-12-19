package com.salesforce.revoman.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.input.json.JsonPojoUtils;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonPojoUtilsTest {

  @Test
  @DisplayName("json file To Pojo")
  void jsonFileToPojo() {
    final var nestedBeanFromJson =
        JsonPojoUtils.<NestedBean>jsonFileToPojo(NestedBean.class, "json/nested-bean.json");
    assertThat(nestedBeanFromJson).isNotNull();
    assertThat(nestedBeanFromJson.getName()).isEqualTo("container");
    assertThat(nestedBeanFromJson.getBean().getItems()).hasSize(2);
  }

  @Test
  @DisplayName("json file with Epoch Date To Pojo")
  void jsonFileWithEpochDateToPojo() {
    final var beanWithDate =
        JsonPojoUtils.<BeanWithDate>jsonFileToPojo(BeanWithDate.class, "json/bean-with-date.json");
    assertThat(beanWithDate).isNotNull();
    assertThat(beanWithDate.date).isNotNull();
  }

  @Test
  @DisplayName("pojo to json")
  void pojoToJson() {
    final var nestedBean = new NestedBean("container", new Bean("bean", List.of("item1", "item2")));
    final var nestedBeanJson = JsonPojoUtils.pojoToJson(NestedBean.class, nestedBean);
    System.out.println(nestedBeanJson);
    assertThat(nestedBeanJson).isNotBlank();
  }

  private static class Bean {
    private final String name;
    private final List<String> items;

    private Bean(String name, List<String> items) {
      this.name = name;
      this.items = items;
    }

    public String getName() {
      return name;
    }

    public List<String> getItems() {
      return items;
    }
  }

  private static class NestedBean {
    private final String name;
    private final Bean bean;

    private NestedBean(String name, Bean bean) {
      this.name = name;
      this.bean = bean;
    }

    public String getName() {
      return name;
    }

    public Bean getBean() {
      return bean;
    }
  }

  private static class BeanWithDate {
    private final Date date;

    private BeanWithDate(Date date) {
      this.date = date;
    }

    public Date getDate() {
      return date;
    }

    @Override
    public String toString() {
      return "BeanWithDate{" + "date=" + date + '}';
    }
  }
}
