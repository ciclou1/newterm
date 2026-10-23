package com.chatbi.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.chatbi.config.ChatBiProperties.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        DruidDataSource ds = base(url, username, password);
        ds.setMinIdle(2);
        ds.setMaxActive(12);
        return ds;
    }

    /**
     * The only connection pool generated SQL is allowed to use. It is configured
     * read-only and points at a database role that has SELECT privileges only.
     */
    @Bean("queryDataSource")
    public DataSource queryDataSource(ChatBiProperties properties) {
        Query q = properties.getQuery();
        DruidDataSource ds = base(q.getUrl(), q.getUsername(), q.getPassword());
        ds.setMinIdle(1);
        ds.setMaxActive(4);
        ds.setDefaultReadOnly(true);
        return ds;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("queryJdbcTemplate")
    public JdbcTemplate queryJdbcTemplate(@Qualifier("queryDataSource") DataSource dataSource) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(8);
        return template;
    }

    @Bean("queryNamedJdbcTemplate")
    public NamedParameterJdbcTemplate queryNamedJdbcTemplate(@Qualifier("queryDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    private DruidDataSource base(String url, String username, String password) {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setInitialSize(1);
        ds.setMinIdle(1);
        ds.setMaxActive(8);
        ds.setMaxWait(5000);
        ds.setValidationQuery("SELECT 1");
        ds.setTestWhileIdle(true);
        ds.setTimeBetweenEvictionRunsMillis(60_000);
        try {
            ds.setFilters("stat,slf4j");
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to configure Druid filters", e);
        }
        return ds;
    }
}
